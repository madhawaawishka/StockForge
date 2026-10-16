# POSTMORTEM-005: Expired reservations kept the shopper's purchase allowance

- **Date found:** 2026-10-13
- **Severity:** Medium — shoppers locked out of products; no oversell
- **Status:** Fixed

## Summary
Each user may hold at most 2 units of a product (FR-4), tracked in `user_product_limits.active_quantity`. Cancelling a
reservation returned the units to stock and to the user's allowance. Expiring one returned them to stock only. A
shopper whose reservation timed out twice could never buy that product again.

## Root cause
- Cancel, release (saga compensation) and expiry were three separate code paths, each moving stock, status and
  counters on its own. Expiry was written as a bulk SQL job and missed the limit counter.
- The expiry job also compared `expires_at` with the application clock, so clock skew between instances could expire
  reservations early or late.
- Nothing checked the counter against the reservation rows, so the drift was invisible.

## Fix
- One component, `ReservationTransitions`, performs release, confirm and expiry; all three move stock, purchase limit,
  status and outbox identically.
- Expiry claims reservations with database time (`expires_at < now()`, `FOR UPDATE SKIP LOCKED`) and processes a batch
  in a fixed lock order — reservations, then limit rows sorted, then inventory rows sorted — so it cannot deadlock with
  reservations taking locks in the same order.
- New invariant enforced by the database: `active_quantity <= limit_quantity`.
- Reconciliation and `scripts/check-invariants.sql` compare the counter with the user's pending + confirmed units.

## Prevention
- `ReservationLifecycleTest` (unit) and `ReliableMessagingIT.anExpiredReservationIsReleasedWellWithinThirtySeconds`
  (with PostgreSQL and Kafka) assert the allowance is returned on expiry.
- `stockforge_inventory_invariant_violations` alerts if any counter drifts.

## Lessons
When several flows perform the same state transition, they should share one implementation. Invariants that span two
tables need a check that compares them.
