#!/usr/bin/env python3
"""Engineering proof: drives the running stack through the failure modes the README claims to survive.

Every number printed here is read back from the system (HTTP responses, PostgreSQL), nothing is canned. Run it against
the local stack:

    docker compose --profile apps --profile tools up -d --wait
    python scripts/demo/engineering_proof.py              # all acts
    python scripts/demo/engineering_proof.py --acts 1 2   # a subset
    python scripts/demo/engineering_proof.py --buyers 300 --stock 50

Acts
    1  Oversell race        N buyers released by a barrier at the same instant compete for S units
    2  Idempotency race     one Idempotency-Key sent by many connections at once creates exactly one reservation
    3  Saga compensation    a failed payment cancels the order and returns the stock
    4  Kafka outage         orders are accepted while the broker is down; the outbox buffers, then drains on recovery
    5  Invariant audit      scripts/check-invariants.sql across all four service schemas

Act 4 stops and starts the Kafka container of the local compose project. Only the standard library is used.
"""
import argparse
import base64
import hashlib
import hmac
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOCAL_SECRET = "stockforge-local-development-only-hmac-key"  # same value as application-local.yml; not a secret
PSQL = ["docker", "compose", "exec", "-T", "postgres", "psql", "--no-psqlrc", "-U", "stockforge_admin", "-d", "stockforge"]


# --------------------------------------------------------------------------------------------------------- plumbing
def token(admin=False, user=None):
    now = int(time.time())
    enc = lambda d: base64.urlsafe_b64encode(json.dumps(d, separators=(",", ":")).encode()).rstrip(b"=").decode()
    head = enc({"alg": "HS256", "typ": "JWT"})
    body = enc({"iss": "stockforge-auth", "aud": ["stockforge-api"], "sub": user or str(uuid.uuid4()),
                "roles": ["ADMIN"] if admin else ["CUSTOMER"], "iat": now, "exp": now + 3600})
    sig = hmac.new(LOCAL_SECRET.encode(), f"{head}.{body}".encode(), hashlib.sha256).digest()
    return f"{head}.{body}.{base64.urlsafe_b64encode(sig).rstrip(b'=').decode()}"


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, bearer=None, body=None, headers=None, timeout=30):
        h = {"Accept": "application/json", **(headers or {})}
        if bearer:
            h["Authorization"] = f"Bearer {bearer}"
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, data=data, headers=h, method=method)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode()
                status, resp_headers = resp.status, dict(resp.headers)
        except urllib.error.HTTPError as e:
            raw, status, resp_headers = e.read().decode(), e.code, dict(e.headers)
        except Exception as e:  # connection refused, timeout: report as status 0, never crash a worker thread
            return 0, {"error": str(e)}, {}, (time.perf_counter() - started) * 1000
        ms = (time.perf_counter() - started) * 1000
        try:
            parsed = json.loads(raw) if raw else {}
        except ValueError:
            parsed = {"raw": raw}
        return status, parsed, resp_headers, ms


def sql(query, attempts=5):
    # `docker compose exec` fails sporadically (the Docker Desktop API returns 5xx for a moment, notably around a
    # container restart), so retry before giving up: a recovery wait must not die on a flaky control plane.
    for attempt in range(1, attempts + 1):
        out = subprocess.run(PSQL + ["-At", "-F", "|", "-c", query], cwd=ROOT, capture_output=True, text=True)
        if out.returncode == 0:
            return [line.split("|") for line in out.stdout.strip().splitlines() if line]
        if attempt == attempts:
            sys.exit(f"psql failed {attempts} times: {out.stderr.strip()}")
        time.sleep(2)


def heading(n, title, claim):
    print(f"\n{'=' * 100}\nACT {n}  {title}\n{'-' * 100}\nclaim: {claim}\n{'=' * 100}")


def verdict(ok, text):
    print(f"  [{'PASS' if ok else 'FAIL'}] {text}")
    return ok


def new_product(api, admin, stock, label):
    sku = f"PROOF-{label}-{uuid.uuid4().hex[:8]}".upper()
    status, body, _, _ = api.call("POST", "/api/v1/admin/products", admin, {
        "sku": sku, "name": f"Engineering proof {label}", "price": {"amount": "19.99", "currency": "USD"},
        "initialStock": stock})
    if status != 201:
        sys.exit(f"cannot create product: {status} {body}")
    return body["id"], sku


def availability(api, pid):
    return api.call("GET", f"/api/v1/products/{pid}/availability")[1]


def wait_for(predicate, timeout, interval=0.5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    return None


# -------------------------------------------------------------------------------------------------------------- acts
def act1(api, admin, buyers, stock):
    heading(1, "OVERSELL RACE", f"{buyers} buyers, {stock} units, released at the same instant: never more than {stock} sold")
    pid, sku = new_product(api, admin, stock, "RACE")
    print(f"  product {sku} ({pid}) created with {stock} units")
    barrier = threading.Barrier(buyers)

    def buyer(_):
        who = token()
        barrier.wait()
        return api.call("POST", "/api/v1/reservations", who, {"productId": pid, "quantity": 1},
                        {"Idempotency-Key": uuid.uuid4().hex})

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=buyers) as pool:
        results = list(pool.map(buyer, range(buyers)))
    wall = time.perf_counter() - started

    codes = Counter(r[0] for r in results)
    print(f"  {buyers} requests finished in {wall * 1000:.0f} ms wall clock")
    for code, count in sorted(codes.items()):
        meaning = {201: "reserved", 409: "sold out (clean RFC 9457 problem)", 429: "rate limited", 0: "client error"}.get(code, "")
        print(f"    HTTP {code}: {count:>4}  {meaning}")
    winners = [r for r in results if r[0] == 201]
    # No latency figures on purpose: this is a 200-thread Python burst from one laptop, so it measures the client.
    # Service latency under open-model load is measured with k6 (docs/benchmarks).

    row = sql(f"SELECT total_quantity, available_quantity, reserved_quantity, sold_quantity "
              f"FROM inventory.inventory WHERE product_id = '{pid}'")[0]
    total, avail, reserved, sold = map(int, row)
    print(f"  database: total={total} available={avail} reserved={reserved} sold={sold}")
    rows = int(sql(f"SELECT COALESCE(SUM(quantity),0) FROM inventory.reservations WHERE product_id='{pid}' "
                   f"AND status IN ('PENDING','CONFIRMED')")[0][0])
    expected = min(buyers, stock)
    return all([
        verdict(len(winners) == expected, f"exactly {expected} reservations succeeded (got {len(winners)})"),
        verdict(reserved + sold <= total, f"held units {reserved + sold} never exceed total {total}"),
        verdict(avail + reserved + sold == total, "available + reserved + sold == total"),
        verdict(rows == reserved + sold, f"reservation rows ({rows}) agree with the counters ({reserved + sold})"),
    ])


def act2(api, admin, connections):
    heading(2, "IDEMPOTENCY RACE", f"one Idempotency-Key sent by {connections} connections at once creates exactly one reservation")
    pid, _ = new_product(api, admin, 10, "IDEM")
    who, key = token(), "same-key-" + uuid.uuid4().hex
    barrier = threading.Barrier(connections)

    def fire(_):
        barrier.wait()
        return api.call("POST", "/api/v1/reservations", who, {"productId": pid, "quantity": 2}, {"Idempotency-Key": key})

    with ThreadPoolExecutor(max_workers=connections) as pool:
        results = list(pool.map(fire, range(connections)))
    codes = Counter(r[0] for r in results)
    replayed = sum(1 for r in results if r[2].get("Idempotent-Replayed") == "true")
    ids = {r[1].get("id") for r in results if r[0] == 201}
    print(f"  statuses {dict(codes)}   replayed flag on {replayed} responses   distinct reservation ids {len(ids)}")
    held = int(sql(f"SELECT COUNT(*) FROM inventory.reservations WHERE product_id='{pid}'")[0][0])
    stock = availability(api, pid)
    print(f"  database: {held} reservation row(s); availability now {stock['availableQuantity']}/{stock['totalQuantity']}")
    # A single user is also rate limited (reservation bucket: 10 burst), so with more connections than that a 429 is
    # correct behaviour, not a failure. The idempotency guarantee is about what was created, so assert on that.
    return all([
        verdict(set(codes) <= {201, 429}, f"every answer is a 201 or a rate-limit 429, nothing else (got {dict(codes)})"),
        verdict(codes.get(201, 0) >= 1 and len(ids) == 1, "every 201 carries the same reservation id"),
        verdict(held == 1, f"one reservation row exists ({held})"),
        verdict(stock["availableQuantity"] == 8, "stock was deducted once: 10 - 2 = 8"),
    ])


def poll_order(api, who, order_id, wanted, timeout=30):
    def check():
        status, body, _, _ = api.call("GET", f"/api/v1/orders/{order_id}", who)
        return body if status == 200 and body.get("status") in wanted else None
    return wait_for(check, timeout)


def checkout(api, who, pid, simulation=None):
    status, res, _, _ = api.call("POST", "/api/v1/reservations", who, {"productId": pid, "quantity": 1},
                                 {"Idempotency-Key": uuid.uuid4().hex})
    if status != 201:
        return status, res, None
    headers = {"Idempotency-Key": uuid.uuid4().hex}
    if simulation:
        headers["X-Payment-Simulation"] = simulation
    status, order, _, _ = api.call("POST", "/api/v1/orders", who, {"reservationId": res["id"]}, headers)
    return status, res, order


def act3(api, admin):
    heading(3, "SAGA COMPENSATION", "a failed payment cancels the order and the stock returns to AVAILABLE")
    pid, _ = new_product(api, admin, 5, "SAGA")
    who = token()
    status, res, order = checkout(api, who, pid, simulation="FAILED")
    print(f"  reserve 201 -> place order {status} (202 means accepted, payment is asynchronous); order {order['id']}")
    final = poll_order(api, who, order["id"], {"CANCELLED", "CONFIRMED"})
    if not final:
        return verdict(False, "order did not reach a final state in 30 s")
    print("  order history, as recorded in orders.order_status_history:")
    for h in final["history"]:
        print(f"    {h['occurredAt'][11:23]}  {h['fromStatus'] or '-':>16} -> {h['toStatus']:<16} {h['reason']}")
    stock = wait_for(lambda: (lambda a: a if a["availableQuantity"] == 5 else None)(availability(api, pid)), 20)
    print(f"  availability after compensation: {stock['availableQuantity'] if stock else '?'}/5")
    return all([
        verdict(status == 202, "POST /orders answered 202 Accepted"),
        verdict(final["status"] == "CANCELLED", f"order is {final['status']}"),
        verdict(stock is not None, "the reservation was released: all 5 units are available again"),
    ])


def act4(api, admin):
    heading(4, "KAFKA OUTAGE", "orders are accepted with the broker down; the outbox buffers and delivers after recovery")
    pid, _ = new_product(api, admin, 20, "CHAOS")
    pending_q = "SELECT COUNT(*) FROM ordering.outbox_events WHERE status = 'PENDING'"
    print(f"  baseline PENDING outbox rows (orders schema): {sql(pending_q)[0][0]}")
    print("  $ docker compose stop kafka")
    subprocess.run(["docker", "compose", "stop", "kafka"], cwd=ROOT, check=True, capture_output=True)
    who_orders = []
    try:
        for _ in range(5):
            who = token()
            status, _, order = checkout(api, who, pid)
            who_orders.append((who, order["id"] if order else None, status))
        accepted = sum(1 for _, oid, s in who_orders if s == 202)
        print(f"  with Kafka DOWN: {accepted}/5 orders answered 202 Accepted")
        time.sleep(3)
        buffered = int(sql(pending_q)[0][0])
        print(f"  PENDING outbox rows now: {buffered}  (events committed with the order, waiting for the broker)")
        state = Counter(poll_state(api, w, o) for w, o, _ in who_orders if o)
        print(f"  order states while the broker is down: {dict(state)}")
    finally:
        print("  $ docker compose start kafka")
        subprocess.run(["docker", "compose", "start", "kafka"], cwd=ROOT, check=True, capture_output=True)
    drained = wait_for(lambda: int(sql(pending_q)[0][0]) == 0, 120, 2)
    print(f"  outbox drained after recovery: {bool(drained)}")
    finals = [poll_order(api, w, o, {"CONFIRMED", "CANCELLED"}, 60) for w, o, _ in who_orders if o]
    states = Counter(f["status"] if f else "TIMEOUT" for f in finals)
    print(f"  final order states: {dict(states)}")
    return all([
        verdict(accepted == 5, "every order was accepted while Kafka was down (no 5xx, no lost request)"),
        verdict(buffered >= 5, f"the events were buffered in the outbox ({buffered} PENDING)"),
        verdict(bool(drained), "the outbox relay delivered everything after the broker came back"),
        verdict(states.get("CONFIRMED", 0) == 5, f"all 5 sagas completed: {dict(states)}"),
    ])


def poll_state(api, who, order_id):
    status, body, _, _ = api.call("GET", f"/api/v1/orders/{order_id}", who)
    return body.get("status", f"HTTP {status}")


def act5():
    heading(5, "INVARIANT AUDIT", "after all of the above, every cross-service invariant still holds")
    # Same invocation as scripts/verify-invariants.sh, without bash: on Windows `bash` may resolve to the WSL launcher.
    cmd = ["docker", "compose", "exec", "-T", "-e", "PGPASSWORD=admin_local_only", "postgres", "psql", "--no-psqlrc",
           "--quiet", "-U", "stockforge_admin", "-d", "stockforge", "-f", "-"]
    out = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True,
                         input=(ROOT / "scripts" / "check-invariants.sql").read_text(encoding="utf-8"))
    print("\n".join("  " + line for line in (out.stdout + out.stderr).strip().splitlines()))
    return verdict(out.returncode == 0, "scripts/check-invariants.sql exited 0 (no violations)")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", default="http://localhost:8088", help="gateway (8088 when 8080 is reserved)")
    p.add_argument("--acts", nargs="*", type=int, default=[1, 2, 3, 4, 5])
    p.add_argument("--buyers", type=int, default=200)
    p.add_argument("--stock", type=int, default=50)
    p.add_argument("--connections", type=int, default=8, help="stay within the per-user burst of 10")
    args = p.parse_args()

    api, admin = Api(args.base_url), token(admin=True)
    runners = {1: lambda: act1(api, admin, args.buyers, args.stock), 2: lambda: act2(api, admin, args.connections),
               3: lambda: act3(api, admin), 4: lambda: act4(api, admin), 5: act5}
    results = {n: runners[n]() for n in args.acts}
    print(f"\n{'=' * 100}\nSUMMARY  " + "   ".join(f"act {n}: {'PASS' if ok else 'FAIL'}" for n, ok in results.items()))
    sys.exit(0 if all(results.values()) else 1)


if __name__ == "__main__":
    main()
