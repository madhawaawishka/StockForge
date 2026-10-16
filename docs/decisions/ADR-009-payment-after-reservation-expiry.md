# ADR-009: A payment that arrives after the reservation expired is refunded
- Status: Accepted
- Date: 2026-10-13

## Context
A reservation holds stock for 10 minutes. The payment for its order may succeed after that — a slow provider, a
retried command, a consumer that was down. By then the expiry job has released the units, and another shopper may
already have reserved them (spec §16 C).

## Options considered
1. **Resurrect the reservation** — confirm it anyway and take the units back. Breaks the promise made to the other
   shopper, and can oversell when the stock is already gone.
2. **Hold stock until the payment settles** — no expiry while a payment is pending. A provider that never answers would
   lock stock forever.
3. **Inventory decides, the saga compensates** — inventory confirms only `PENDING` reservations. Otherwise it answers
   `ReservationConfirmationRejected`, and the order saga refunds the payment.

## Decision
Option 3. Inventory is the single authority on stock; the reservation state machine has no transition out of
`EXPIRED` or `CANCELLED`. The order moves `PENDING_PAYMENT → REFUND_PENDING → REFUNDED` and the customer is notified.
The same path handles a payment that succeeds after the order was cancelled by the payment timeout.

## Consequences
- Overselling is impossible on this path; the worst case is a refund, which is visible
  (`stockforge_saga_refunds_total`) and alertable.
- Expiry uses database time (`now()` in the claiming query), so application clock skew cannot expire reservations
  early or late.
- The refund is itself an idempotent command: one payment per order (unique constraint), refunding twice is a no-op.

## Evidence
- `CheckoutSagaTest`: late `PaymentSucceeded` after cancellation → `RefundPayment`; confirmation rejected → refund.
- `ReservationLifecycleTest`: an expired reservation cannot be confirmed; expiry releases the purchase limit too.
