#!/usr/bin/env bash
# Checks, against a running stack, that what README.md promises actually works: the checkout walkthrough, every
# status code in the API table, ownership rules, and the operational URLs (API docs, Grafana, Prometheus, Kafka UI,
# traces in Tempo). Exits non-zero on the first broken promise.
#
#   docker compose --profile apps --profile tools up -d --build --wait
#   bash scripts/verify-readme.sh                    # BASE_URL=http://localhost:8088 if the gateway uses another port
#
# Needs curl and python3 (JSON parsing, token minting); talks to Tempo and Kafka through `docker compose exec`.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:${GATEWAY_PUBLIC_PORT:-8080}}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
KAFKA_UI_URL="${KAFKA_UI_URL:-http://localhost:8090}"
# The first interpreter that actually runs (on Windows, "python3" may be a Microsoft Store stub that only prints a hint).
PYTHON=""
for candidate in python3 python; do
    if "$candidate" -c 'import sys' > /dev/null 2>&1; then PYTHON=$candidate; break; fi
done
[ -n "$PYTHON" ] || { echo "python3 is required" >&2; exit 1; }

passed=0
pass() { passed=$((passed + 1)); printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1" >&2; [ -n "${2:-}" ] && printf '       %s\n' "$2" >&2; exit 1; }
section() { printf '\n%s\n' "$1"; }
json() { "$PYTHON" -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {}, {'d': d}))" "$1"; }
token() { "$PYTHON" scripts/dev-token.py "$@"; }
key() { "$PYTHON" -c "import uuid; print(uuid.uuid4())"; }

# request METHOD PATH TOKEN [BODY] [extra curl args...] -> sets STATUS, BODY, HEADERS
request() {
    local method=$1 path=$2 bearer=$3 body=${4:-}
    shift 4 || shift $#
    local headers_file body_file
    headers_file=$(mktemp) body_file=$(mktemp)
    local args=(-s -o "$body_file" -D "$headers_file" -w '%{http_code}' -X "$method" "$BASE_URL$path")
    [ -n "$bearer" ] && args+=(-H "Authorization: Bearer $bearer")
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
    STATUS=$(curl "${args[@]}" "$@")
    BODY=$(cat "$body_file")
    HEADERS=$(tr -d '\r' < "$headers_file")
    rm -f "$headers_file" "$body_file"
}
expect() { if [ "$STATUS" = "$1" ]; then pass "$2"; else fail "$2 (expected $1, got $STATUS)" "$BODY"; fi; }
header() { printf '%s\n' "$HEADERS" | grep -i "^$1:" | head -1 | cut -d' ' -f2-; }

ADMIN=$(token --admin)
ME=$(token)
SOMEONE_ELSE=$(token)
SKU="README-$(date +%s)-$RANDOM"

section "Checkout walkthrough (README 'Try a checkout')"
request POST /api/v1/admin/products "$ADMIN" \
    "{\"sku\":\"$SKU\",\"name\":\"Limited sneaker\",\"price\":{\"amount\":\"119.00\",\"currency\":\"USD\"},\"initialStock\":100}"
expect 201 "admin creates a product"
PRODUCT=$(json 'd["id"]' <<< "$BODY")

RES_KEY=$(key)
request POST /api/v1/reservations "$ME" "{\"productId\":\"$PRODUCT\",\"quantity\":1}" -H "Idempotency-Key: $RES_KEY"
expect 201 "shopper reserves 1 unit"
RESERVATION=$(json 'd["id"]' <<< "$BODY")
request POST /api/v1/reservations "$ME" "{\"productId\":\"$PRODUCT\",\"quantity\":1}" -H "Idempotency-Key: $RES_KEY"
if [ "$STATUS" = 201 ] && [ "$(json 'd["id"]' <<< "$BODY")" = "$RESERVATION" ] \
    && [ "$(header Idempotent-Replayed)" = true ]; then
    pass "a retry with the same Idempotency-Key replays the original reservation"
else
    fail "idempotent replay" "$STATUS $BODY"
fi

request POST /api/v1/orders "$ME" "{\"reservationId\":\"$RESERVATION\"}" -H "Idempotency-Key: $(key)"
expect 202 "placing the order is accepted asynchronously"
ORDER=$(json 'd["id"]' <<< "$BODY")
if [ "$(header Location)" = "/api/v1/orders/$ORDER" ]; then pass "202 carries the order's Location"; else fail "Location header" "$HEADERS"; fi

status=PENDING_PAYMENT
for _ in $(seq 1 60); do
    request GET "/api/v1/orders/$ORDER" "$ME"
    status=$(json 'd["status"]' <<< "$BODY")
    [ "$status" != PENDING_PAYMENT ] && break
    sleep 0.5
done
if [ "$status" = CONFIRMED ]; then pass "the saga confirms the order"; else fail "order status is $status" "$BODY"; fi
if [ "$(json 'len(d["history"])' <<< "$BODY")" -ge 2 ]; then pass "the order carries its status history"; else fail "history" "$BODY"; fi

section "Public API table"
request GET /api/v1/products "" ""
expect 200 "product list is public"
request GET "/api/v1/products?limit=0" "" ""
expect 400 "page size is validated"
request GET "/api/v1/products/$PRODUCT" "" ""
expect 200 "product detail is public"
ETAG=$(header ETag)
request GET "/api/v1/products/$PRODUCT" "" "" -H "If-None-Match: $ETAG"
expect 304 "product detail answers 304 to its ETag"
request GET "/api/v1/products/$PRODUCT/availability" "" ""
expect 200 "availability is public"
if [ "$(json 'd["availableQuantity"]' <<< "$BODY")" -le 99 ]; then pass "availability reflects the sale"; else fail "availability" "$BODY"; fi
ETAG=$(header ETag)
request GET "/api/v1/products/$PRODUCT/availability" "" "" -H "If-None-Match: $ETAG"
expect 304 "availability answers 304 while nothing changed"

request POST /api/v1/reservations "" "{\"productId\":\"$PRODUCT\",\"quantity\":1}" -H "Idempotency-Key: $(key)"
expect 401 "writes need a token"
case "$(header Content-Type)" in application/problem+json*) pass "errors are RFC 9457 problem+json" ;; *) fail "problem content type" "$HEADERS" ;; esac
request POST /api/v1/reservations "$ME" "{\"productId\":\"$PRODUCT\",\"quantity\":1}"
expect 400 "a reservation needs an Idempotency-Key"
request POST /api/v1/reservations "$ME" "{\"productId\":\"$PRODUCT\",\"quantity\":3}" -H "Idempotency-Key: $(key)"
expect 422 "more than the purchase limit is 422"

request POST /api/v1/admin/products "$ADMIN" \
    "{\"sku\":\"$SKU-ONE\",\"name\":\"Last unit\",\"price\":{\"amount\":\"5.00\",\"currency\":\"USD\"},\"initialStock\":1}"
ONE_LEFT=$(json 'd["id"]' <<< "$BODY")
request POST /api/v1/reservations "$(token)" "{\"productId\":\"$ONE_LEFT\",\"quantity\":1}" -H "Idempotency-Key: $(key)"
expect 201 "the last unit can be reserved"
request POST /api/v1/reservations "$(token)" "{\"productId\":\"$ONE_LEFT\",\"quantity\":1}" -H "Idempotency-Key: $(key)"
expect 409 "after that the product is sold out (409)"

request GET "/api/v1/reservations/$RESERVATION" "$ME"
expect 200 "the owner reads the reservation"
request GET "/api/v1/reservations/$RESERVATION" "$SOMEONE_ELSE"
expect 404 "another user gets 404 for it"
request GET "/api/v1/orders/$ORDER" "$SOMEONE_ELSE"
expect 404 "and 404 for the order"

request POST /api/v1/reservations "$ME" "{\"productId\":\"$ONE_LEFT\",\"quantity\":1}" -H "Idempotency-Key: $(key)"
request POST /api/v1/reservations "$ME" "{\"productId\":\"$PRODUCT\",\"quantity\":1}" -H "Idempotency-Key: $(key)"
CANCELLABLE=$(json 'd["id"]' <<< "$BODY")
request POST "/api/v1/reservations/$CANCELLABLE/cancel" "$ME" ""
if [ "$STATUS" = 200 ] && [ "$(json 'd["status"]' <<< "$BODY")" = CANCELLED ]; then pass "the owner cancels a pending reservation"; else fail "cancel" "$BODY"; fi
request POST "/api/v1/reservations/$CANCELLABLE/cancel" "$ME" ""
expect 409 "cancelling twice is a 409 state conflict"

# A second order, so that a page of one has a next page. (The shopper now holds 2 units: the purchase limit.)
request POST /api/v1/reservations "$ME" "{\"productId\":\"$PRODUCT\",\"quantity\":1}" -H "Idempotency-Key: $(key)"
expect 201 "the shopper reserves a second unit, up to the purchase limit"
request POST /api/v1/orders "$ME" "{\"reservationId\":\"$(json 'd["id"]' <<< "$BODY")\"}" -H "Idempotency-Key: $(key)"
expect 202 "and orders it"
request GET "/api/v1/orders?limit=1" "$ME"
expect 200 "the owner lists their orders"
CURSOR=$(json 'd.get("nextCursor") or ""' <<< "$BODY")
if [ -n "$CURSOR" ]; then pass "a full page carries a nextCursor (keyset pagination)"; else fail "nextCursor" "$BODY"; fi
request GET "/api/v1/orders?limit=1&cursor=$CURSOR" "$ME"
if [ "$STATUS" = 200 ] && [ "$(json 'len(d["items"])' <<< "$BODY")" = 1 ]; then pass "the cursor returns the next page"; else fail "page 2" "$BODY"; fi

request POST "/api/v1/admin/products/$PRODUCT/stock-adjustments" "$ME" '{"delta":5,"reason":"restock"}' -H "Idempotency-Key: $(key)"
expect 403 "a shopper cannot adjust stock"
ADJ_KEY=$(key)
request POST "/api/v1/admin/products/$PRODUCT/stock-adjustments" "$ADMIN" '{"delta":5,"reason":"restock"}' -H "Idempotency-Key: $ADJ_KEY"
expect 201 "an admin adjusts stock"
request POST "/api/v1/admin/products/$PRODUCT/stock-adjustments" "$ADMIN" '{"delta":5,"reason":"restock"}' -H "Idempotency-Key: $ADJ_KEY"
if [ "$(header Idempotent-Replayed)" = true ]; then pass "a repeated adjustment is replayed, not applied twice"; else fail "adjustment replay" "$HEADERS"; fi

limited=""
BURSTER=$(token)
for _ in $(seq 1 15); do
    request POST /api/v1/reservations "$BURSTER" "{\"productId\":\"$PRODUCT\",\"quantity\":1}" -H "Idempotency-Key: $(key)"
    [ "$STATUS" = 429 ] && { limited=yes; break; }
done
if [ -n "$limited" ] && [ -n "$(header Retry-After)" ]; then pass "a burst from one user is rate limited (429 + Retry-After)"; else fail "rate limit" "$STATUS $BODY"; fi

section "Dead-letter replay"
MSYS_NO_PATHCONV=1 docker compose exec -T kafka sh -c \
    'echo "readme-check:{\"not\":\"an envelope\"}" | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payment-events.DLT --property parse.key=true --property key.separator=:' \
    > /dev/null
request POST "/api/v1/admin/dead-letters/payment-events/replay?maxMessages=10" "$ADMIN" ""
if [ "$STATUS" = 202 ] && [ "$(json 'd["replayed"]' <<< "$BODY")" -ge 1 ]; then pass "an admin replays dead letters (202, $(json 'd["replayed"]' <<< "$BODY") replayed)"; else fail "replay" "$STATUS $BODY"; fi
request POST "/api/v1/admin/dead-letters/not-a-topic/replay" "$ADMIN" ""
expect 400 "only the platform's topics can be replayed"

section "Operational URLs"
request GET /swagger-ui.html "" "" -L
expect 200 "Swagger UI is served"
request GET /v3/api-docs "" ""
if [ "$STATUS" = 200 ] && [ "$(json 'len(d["paths"])' <<< "$BODY")" -ge 10 ]; then pass "OpenAPI document lists the API ($(json 'len(d["paths"])' <<< "$BODY") paths)"; else fail "api docs" "$STATUS"; fi
request GET /actuator/prometheus "" ""
if [ "$STATUS" != 200 ]; then pass "metrics are not exposed on the public port ($STATUS)"; else fail "actuator exposed publicly"; fi

titles=$(curl -s "$GRAFANA_URL/api/search?query=StockForge" | json '",".join(sorted(x["title"] for x in d if x.get("type") == "dash-db"))')
case "$titles" in *"Business flow"*"System overview"*) pass "Grafana has the StockForge dashboards ($titles)" ;; *) fail "Grafana dashboards" "$titles" ;; esac
rules=$(curl -s "$PROMETHEUS_URL/api/v1/rules" | json 'sum(len(g["rules"]) for g in d["data"]["groups"])')
if [ "$rules" -ge 20 ]; then pass "Prometheus loaded $rules alerting and recording rules"; else fail "rules" "$rules"; fi
down=$(curl -s "$PROMETHEUS_URL/api/v1/query" --data-urlencode 'query=max by (application) (up) == 0' | json 'len(d["data"]["result"])')
if [ "$down" = 0 ]; then pass "Prometheus scrapes all five services"; else fail "$down services not scraped"; fi
if curl -sf "$KAFKA_UI_URL/api/clusters" > /dev/null 2>&1; then
    state=$(curl -s "$KAFKA_UI_URL/api/clusters" | json 'd[0]["status"].upper()')
    if [ "$state" = ONLINE ]; then pass "Kafka UI sees the cluster"; else fail "Kafka UI cluster status $state"; fi
else
    printf '  skip Kafka UI (start it with: docker compose --profile tools up -d)\n'
fi

section "Traces through Kafka"
services=""
for _ in $(seq 1 20); do
    trace=$(MSYS_NO_PATHCONV=1 docker compose exec -T observability curl -s -G localhost:3200/api/search \
        --data-urlencode 'q={resource.service.name="api-gateway" && name=~"http post /api/v1/orders.*"}' \
        --data-urlencode limit=1 --data-urlencode start="$(( $(date +%s) - 600 ))" --data-urlencode end="$(date +%s)" \
        | json '(d.get("traces") or [{}])[0].get("traceID", "")')
    if [ -n "$trace" ]; then
        services=$(MSYS_NO_PATHCONV=1 docker compose exec -T observability curl -s "localhost:3200/api/traces/$trace" \
            | json '",".join(sorted({a["value"]["stringValue"] for b in d.get("batches", []) for a in b["resource"]["attributes"] if a["key"] == "service.name"}))')
        case "$services" in *notification-worker*) break ;; esac
    fi
    sleep 3
done
case "$services" in
    *api-gateway*inventory-service*notification-worker*order-service*payment-service*)
        pass "one checkout is one trace across all five services ($services)" ;;
    *) fail "checkout trace spans: ${services:-none found}" ;;
esac

printf '\nAll %d README checks passed against %s\n' "$passed" "$BASE_URL"
