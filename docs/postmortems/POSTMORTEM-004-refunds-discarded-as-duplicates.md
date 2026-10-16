# POSTMORTEM-004: Refund commands were discarded as duplicates

- **Date found:** 2026-10-13
- **Severity:** High — customers would have been charged for orders they never received
- **Status:** Fixed

## Summary
The payment service ignored every `RefundPayment` command. Its consumer deduplicated messages by the Kafka record
key, which is the order id. `RequestPayment` and `RefundPayment` for the same order share that key, so once the payment
had been processed, the refund looked like a redelivery of it and was dropped.

## Impact
Every compensation that needed a refund — payment succeeded after the order was cancelled, or after the reservation
expired (ADR-009) — would have left the customer charged with a cancelled order.

## Detection
Code review during the audit, while checking that each consumer's idempotency key identifies the *message* and not the
*aggregate*.

## Root cause
The inbox used the wrong identity. The partition key is chosen for ordering (all messages of one aggregate on one
partition, ADR-008); it says nothing about whether two messages are the same message. In addition, the inbox insert ran
in its own transaction before the business change, so a crash in between would have marked a message processed without
its effect.

## Fix
- Every envelope carries a unique `eventId` (UUIDv7), also sent as the `stockforge-event-id` header; consumers record
  `(consumer_group, event_id)` in `processed_events`.
- The inbox insert runs in the same transaction as the business change (`ProcessedMessageStore.markProcessed` requires
  an active transaction).
- One payment per order (unique constraint); a refund of an already refunded payment is acknowledged without effect.

## Prevention
- `PaymentIT.aRefundCommandReturnsTheMoney` sends a payment request and then a refund with the same record key (the
  order id) and asserts the refund happens.
- `MessagingIT` and `ReliableMessagingIT` deliver the same event twice and assert a single effect.

## Lessons
"Idempotent consumer" needs a precise definition of *same message*. A key chosen for routing is not an identity.
