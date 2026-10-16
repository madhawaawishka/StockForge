# StockForge architecture

C4 views (context → containers → components) and the sequence diagrams of the flows that matter for correctness.
Diagrams are Mermaid, so they render on GitHub and stay next to the code they describe. Decisions behind them are in
[`docs/decisions/`](../decisions/README.md).

## 1. System context (C4 level 1)

```mermaid
flowchart LR
    shopper(["Shopper<br/><i>browser / mobile app</i>"])
    admin(["Operator<br/><i>catalog and stock admin</i>"])
    idp["Identity provider<br/><i>external, issues JWTs</i>"]
    sf["<b>StockForge</b><br/>Reserves scarce stock under heavy contention,<br/>runs checkout and payment as a saga"]
    psp["Payment provider<br/><i>simulated in this project</i>"]
    mail["E-mail / SMS gateway<br/><i>simulated (logging sender)</i>"]

    shopper -- "browse, reserve, order<br/>(HTTPS, JSON)" --> sf
    admin -- "create products, adjust stock,<br/>replay dead letters" --> sf
    idp -. "signs access tokens<br/>(HS256 locally)" .-> shopper
    sf -- "charge / refund" --> psp
    sf -- "order notifications" --> mail
```

There is no identity provider in the repository: the gateway validates HS256 tokens signed with a configured key
([`scripts/dev-token.py`](../../scripts/dev-token.py) mints them locally). A real deployment would point the resource
server at an issuer's JWKS endpoint; nothing else changes (ADR-011).

## 2. Containers (C4 level 2)

```mermaid
flowchart TB
    client(["Clients"])

    subgraph edge["Public edge"]
        gw["<b>api-gateway</b> :8080<br/>Spring MVC · virtual threads<br/>JWT auth, object-level authz,<br/>rate limits, RFC 9457 errors"]
    end

    subgraph core["Services (private network)"]
        inv["<b>inventory-service</b> :8081 · gRPC :9091<br/>catalog, stock, reservations,<br/>expiry + reconciliation jobs"]
        ord["<b>order-service</b> :8082 · gRPC :9092<br/>orders, checkout saga orchestrator"]
        pay["<b>payment-service</b> :8083<br/>payments (simulated provider)"]
        ntf["<b>notification-worker</b> :8084<br/>customer notifications"]
    end

    subgraph data["Data and messaging"]
        pg[("<b>PostgreSQL 18</b><br/>one schema + role per service:<br/>inventory · ordering · payment · notification")]
        redis[("<b>Redis 8</b><br/>product cache, rate-limit buckets")]
        kafka{{"<b>Kafka 4</b> (KRaft)<br/>inventory-events · inventory-commands<br/>order-events · payment-commands · payment-events<br/>+ &lt;topic&gt;.DLT"}}
    end

    obs["<b>Observability</b><br/>Prometheus · Grafana · Tempo<br/>(grafana/otel-lgtm)"]

    client -- "REST/JSON" --> gw
    gw -- "gRPC, 2 s deadline,<br/>circuit breaker + bulkhead" --> inv
    gw -- "gRPC" --> ord
    gw -- "token buckets (Lua)" --> redis
    ord -- "gRPC: reservation + price" --> inv
    inv --> pg
    ord --> pg
    pay --> pg
    ntf --> pg
    inv -- "cache-aside" --> redis
    inv <-- "outbox relay / consumers" --> kafka
    ord <--> kafka
    pay <--> kafka
    kafka --> ntf
    gw & inv & ord & pay & ntf -. "traces (OTLP), metrics (scrape)" .-> obs
```

| Container | Owns | Talks to | Why separate |
| :--- | :--- | :--- | :--- |
| api-gateway | nothing persistent | inventory, order (gRPC); Redis | single place for authentication, authorization, rate limiting and the public error format |
| inventory-service | `inventory` schema | PostgreSQL, Redis, Kafka | the contended write path; scales and is tuned independently |
| order-service | `ordering` schema | inventory (gRPC), PostgreSQL, Kafka | orchestrates the saga; owns order state and history |
| payment-service | `payment` schema | PostgreSQL, Kafka, payment provider | isolates the slow, unreliable external call |
| notification-worker | `notification` schema | PostgreSQL, Kafka | pure consumer; its failures never affect checkout |

Services never read another service's schema (ADR-002); they share only contracts: the gRPC protos in
[`libs/proto`](../../libs/proto) and the event payloads in [`libs/event-schemas`](../../libs/event-schemas). The
transactional outbox, inbox and dead-letter handling are one library, [`libs/messaging`](../../libs/messaging)
(ADR-013).

## 3. Components of the inventory service (C4 level 3)

Every service has the same hexagonal layout, enforced by ArchUnit (ADR-004).

```mermaid
flowchart LR
    subgraph in["adapter.in"]
        grpcIn["InventoryGrpcService<br/>+ exception handler"]
        web["REST controllers<br/>(direct access, tests)"]
        kin["ReservationCommandListener<br/>(Kafka)"]
        jobs["InventoryJobs<br/>expiry · reconciliation · key purge"]
    end
    subgraph app["application (use cases, transactions)"]
        reserve["ReserveStockUseCase"]
        cancel["CancelReservationUseCase"]
        handler["ReservationCommandHandler"]
        transitions["ReservationTransitions<br/>(one lock order for all flows)"]
        catalog["ProductCatalogQueries<br/>(single-flight cache misses)"]
        adjust["AdjustStockUseCase · CreateProductUseCase"]
    end
    subgraph domain["domain (pure Java)"]
        model["Inventory · Reservation · Product<br/>value objects, sealed results"]
        ports["ports: repositories, cache,<br/>outbox, audit log, clock"]
    end
    subgraph out["adapter.out"]
        jdbc["JDBC repositories<br/>+ reservation strategies"]
        cache["RedisProductCache<br/>(circuit breaker)"]
        msg["outbox / inbox<br/>(libs/messaging)"]
    end

    grpcIn & web --> reserve & cancel & catalog & adjust
    kin --> handler
    jobs --> transitions
    handler --> transitions
    cancel --> transitions
    reserve & transitions & catalog & adjust --> model
    reserve & transitions & catalog & adjust --> ports
    jdbc & cache & msg -. implement .-> ports
```

## 4. Key flows

### 4.1 Reserve stock (the hot path)

One database transaction holds the idempotency record, the purchase-limit counter, the stock decrement, the
reservation row and the outbox event, so they commit or roll back together (spec §19). Locks are always taken in the
same order — idempotency key, purchase-limit row, inventory row — which rules out deadlocks between flows.

```mermaid
sequenceDiagram
    autonumber
    actor S as Shopper
    participant G as api-gateway
    participant R as Redis
    participant I as inventory-service
    participant P as PostgreSQL (inventory)
    participant K as Kafka

    S->>G: POST /api/v1/reservations<br/>Bearer JWT, Idempotency-Key
    G->>G: validate JWT (iss, aud, exp, sub = UUID)
    G->>R: token bucket for (user, "reservations")
    alt bucket empty
        G-->>S: 429 + Retry-After
    end
    G->>I: ReserveStock (deadline 2 s, breaker, bulkhead)
    I->>P: BEGIN
    I->>P: INSERT idempotency key ON CONFLICT DO NOTHING
    alt key already completed
        I->>P: SELECT stored outcome
        I-->>G: original result (replayed)
    end
    I->>P: UPSERT purchase limit WHERE active + qty <= limit
    I->>P: UPDATE inventory SET available = available - qty<br/>WHERE available >= qty
    alt 0 rows updated
        I->>P: store outcome "insufficient stock" · COMMIT
        I-->>G: FAILED_PRECONDITION (insufficient-stock)
        G-->>S: 409 problem+json
    end
    I->>P: INSERT reservation (PENDING, expires_at)
    I->>P: INSERT outbox_events (ReservationCreated)
    I->>P: complete idempotency key · COMMIT
    I-->>G: Reservation
    G-->>S: 201 Created + Location
    Note over I,K: later, asynchronously
    I->>P: relay: SELECT … FOR UPDATE SKIP LOCKED
    I->>K: publish (acks=all), mark PUBLISHED after the ack
```

### 4.2 Checkout saga — happy path

The order service orchestrates; every step is a local transaction that also writes the next command to its outbox.
No service calls another over the network inside a transaction.

```mermaid
sequenceDiagram
    autonumber
    actor S as Shopper
    participant G as api-gateway
    participant O as order-service
    participant I as inventory-service
    participant Pay as payment-service
    participant N as notification-worker

    S->>G: POST /api/v1/orders {reservationId}
    G->>O: CreateOrder (gRPC)
    O->>I: GetReservation + price (gRPC, outside any transaction)
    O->>O: TX: insert order PENDING_PAYMENT (one per reservation)<br/>+ outbox RequestPayment, OrderCreated
    O-->>G: order
    G-->>S: 202 Accepted + Location
    O-)Pay: payment-commands: RequestPayment
    Pay->>Pay: TX1 register payment (one per order)
    Pay->>Pay: call provider (no transaction open, timeout)
    Pay->>Pay: TX2 record outcome + outbox PaymentSucceeded
    Pay-)O: payment-events: PaymentSucceeded
    O->>O: TX: step CONFIRMING_RESERVATION + outbox ConfirmReservation
    O-)I: inventory-commands: ConfirmReservation
    I->>I: TX: reservation CONFIRMED, reserved → sold<br/>+ outbox ReservationConfirmed
    I-)O: inventory-events: ReservationConfirmed
    O->>O: TX: order CONFIRMED + outbox OrderConfirmed
    O-)N: order-events: OrderConfirmed
    N->>N: register once per event id, send, mark SENT
    S->>G: GET /api/v1/orders/{id}
    G-->>S: 200 CONFIRMED (+ status history)
```

### 4.3 Compensation — payment fails or never answers

```mermaid
sequenceDiagram
    autonumber
    participant O as order-service
    participant Pay as payment-service
    participant I as inventory-service

    alt provider declines
        Pay-)O: PaymentFailed
    else no outcome within ORDER_PAYMENT_TIMEOUT (2 min)
        O->>O: timeout job claims the order (SKIP LOCKED)
    end
    O->>O: TX: order CANCELLED + outbox ReleaseReservation, OrderCancelled
    O-)I: ReleaseReservation
    I->>I: TX: reservation CANCELLED, stock and purchase limit released
```

### 4.4 Payment arrives after the reservation expired

The reservation's expiry and the payment race. Inventory decides: confirming an expired reservation is rejected, and
the saga refunds the payment instead of selling stock that may already belong to someone else (ADR-009).

```mermaid
sequenceDiagram
    autonumber
    participant O as order-service
    participant Pay as payment-service
    participant I as inventory-service

    I->>I: expiry job: reservation EXPIRED, stock released
    Pay-)O: PaymentSucceeded (late)
    O-)I: ConfirmReservation
    I->>I: reservation is not PENDING → no stock change
    I-)O: ReservationConfirmationRejected (status EXPIRED)
    O->>O: TX: order REFUND_PENDING + outbox RefundPayment
    O-)Pay: RefundPayment
    Pay-)O: PaymentRefunded
    O->>O: TX: order REFUNDED + outbox OrderRefunded
```

### 4.5 Delivery guarantees in one picture

```mermaid
flowchart LR
    tx["business TX<br/>state change + outbox row"] --> relay["relay<br/>SKIP LOCKED, in seq order"]
    relay -- "acks=all, idempotent producer" --> topic["topic<br/>key = aggregate id"]
    topic --> consumer["consumer TX<br/>INSERT processed_events (group, event id)<br/>+ business change"]
    consumer -- "duplicate id: skip" --> done(["exactly-once effect"])
    consumer -- "4 attempts with backoff fail" --> dlt["&lt;topic&gt;.DLT<br/>alert + admin replay"]
```

At-least-once delivery plus idempotent consumers gives an exactly-once *effect*. Ordering is per aggregate: events
for one reservation or order share a partition key (ADR-008).
