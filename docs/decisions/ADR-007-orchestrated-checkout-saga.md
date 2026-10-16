# ADR-007: Orchestrated saga for checkout
- Status: Accepted
- Date: 2026-10-13

## Context
Checkout spans three services that each own their data: inventory (the reservation), payment (the charge) and
order (the order itself). A distributed transaction (2PC) across them is not available with Kafka and PostgreSQL
schemas owned by different services, and would couple their availability. The workflow also has real failure
branches: the payment can fail, time out or arrive after the reservation expired (spec §16).

## Options considered
1. **Choreography** — each service reacts to the others' events. No central component, but the workflow exists only
   implicitly across five listeners; timeouts and "what step is this order in?" have no owner.
2. **Orchestration** — the order service drives the workflow: it sends commands (`RequestPayment`,
   `ConfirmReservation`, `ReleaseReservation`, `RefundPayment`) and reacts to their outcomes.
3. **Synchronous calls** — the order service calls payment and inventory over gRPC inside its request. Simple, but a
   slow provider holds the user's request and a database transaction open, and a crash mid-way loses the step.

## Decision
Orchestration (option 2). The order row carries the saga state explicitly: `status` (what the customer sees) and
`saga_step` (`PAYMENT_REQUESTED`, `CONFIRMING_RESERVATION`, `REFUNDING`, `COMPLETED`), constrained to valid
combinations by a CHECK. Every step is one local transaction that changes the order and writes the next command to
the outbox (ADR-008), so a crash between steps loses nothing. A scheduled job cancels orders that waited longer than
`order.checkout.payment-timeout` (2 min) for a payment outcome.

Handlers only act when the order is in the expected `(status, saga_step)`; anything else is a duplicate or a late
message and is ignored, which makes every handler idempotent on top of the inbox.

## Consequences
- The whole workflow is readable in one class (`CheckoutSaga`) and testable with in-memory fakes.
- The order service is the only component that knows the workflow; payment and inventory expose plain commands.
- Compensation is explicit and tested: release the reservation on payment failure or timeout, refund a payment that
  succeeded after the order was cancelled or the reservation expired (ADR-009).
- The order API is asynchronous: `POST /orders` answers `202 Accepted`, clients poll the order (spec §12).

## Evidence
- `CheckoutSagaTest` (unit) covers every transition and late/duplicate message.
- `CheckoutSagaIT` runs the saga against PostgreSQL, Kafka and an in-process inventory gRPC fake.
- `load-tests/checkout.js` drives checkouts with a share of failed payments through the full compose stack.
