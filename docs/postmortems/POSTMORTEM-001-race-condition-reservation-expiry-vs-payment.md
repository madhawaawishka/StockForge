# POSTMORTEM-001: Race Condition Between Reservation Expiry and Payment Completion

**Date:** 2026-10-08  
**Authors:** SRE & Core Platform Team  
**Status:** Resolved  
**Severity:** P1 (Financial and Inventory Discrepancy)  

---

## 1. Executive Summary

During a flash sale simulation test, 14 orders were placed where a reservation's TTL expired at the exact millisecond that a payment confirmation webhook was received. The background reservation expiry job transitioned the reservation to `EXPIRED` and restored `available_quantity` in the inventory table, while the payment saga concurrently transitioned the order to `CONFIRMED`. A subsequent buyer reserved the restored units, resulting in physical overselling of 14 units.

---

## 2. Impact
- **Financial & Inventory Impact:** 14 units of SKU `SKU-HOT-001` were double-allocated.
- **Duration:** 18 minutes until automated invariant reconciliation alerted on `stockforge_inventory_invariant_violations > 0`.
- **Customer Impact:** 14 customers had to be contacted and issued compensatory discount vouchers.

---

## 3. Timeline (UTC)

- **14:00:00** - Flash sale starts for 500 units of `SKU-HOT-001`.
- **14:14:59.850** - User A submits payment for reservation `RES-8821` (TTL: 15 minutes, expires at 14:15:00.000).
- **14:15:00.002** - `InventoryReconciliationJob` / Expiry worker runs: `UPDATE reservations SET status = 'EXPIRED' WHERE expires_at < NOW() AND status = 'PENDING'`. It marks `RES-8821` as `EXPIRED` and increments `available_quantity` by 1.
- **14:15:00.008** - Payment provider webhook confirms `PaymentSucceeded` for `RES-8821`. Order service marks order as `CONFIRMED`.
- **14:15:00.040** - User B issues `POST /api/v1/reservations` and reserves the single unit newly released by User A's "expired" reservation.
- **14:18:00** - Scheduled reconciliation job detects `stock conservation mismatch: total physical = 500, sum of confirmed orders and pending = 514`.
- **14:18:15** - Alert `StockInvariantViolation` fires; on-call engineer paged.

---

## 4. Root Cause Analysis (5 Whys)

1. **Why did overselling occur?**  
   Stock was restored to the inventory table while the payment for that exact stock was accepted.
2. **Why was stock restored if payment succeeded?**  
   The expiry job executed an unconditional batch update on expired rows without checking whether an active payment processing lock or intent existed.
3. **Why did the payment saga confirm an expired reservation?**  
   The order service payment handler assumed that receiving `PaymentSucceeded` implied the reservation was still guaranteed without re-verifying the reservation state with an atomic CAS check.
4. **Why was there no shared concurrency lock?**  
   Reservations and Orders live in separate microservice boundaries with independent databases (ADR-002).
5. **Why was the distributed race not prevented?**  
   The system lacked a compensating transaction flow: if payment arrives for an already expired reservation, the saga must trigger an immediate automated refund rather than blindly confirming the order.

---

## 5. Corrective & Preventative Actions

| Action Item | Type | Owner | Status |
| :--- | :--- | :--- | :--- |
| Enforce CAS status check: `UPDATE reservations SET status = 'CONFIRMED' WHERE id = :id AND status = 'PENDING'` | Preventative | Inventory Team | Done |
| Implement Saga compensation: If reservation CAS returns 0 (already expired), Order Service immediately issues `RefundPaymentCommand` | Mitigative | Order Team | Done |
| Add 30-second grace period in expiry worker: Expiry job only sweeps reservations where `expires_at < (NOW() - INTERVAL '30 SECONDS')` | Preventative | Inventory Team | Done |
| Add end-to-end chaos test simulating payment arrival at TTL boundary | Detective | QA / Chaos Team | Done |
