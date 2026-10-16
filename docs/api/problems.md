# API problem types

Every error response from StockForge uses [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) with
media type `application/problem+json`. The `type` is a link to the matching section of this page; `traceId` links the
response to its distributed trace and logs.

```json
{
  "type": "https://github.com/madhawaawishka/StockForge/blob/main/docs/api/problems.md#insufficient-stock",
  "title": "Insufficient stock",
  "status": 409,
  "detail": "Only 0 units of product 0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10 are available",
  "instance": "/api/v1/reservations",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

Clients branch on `type` (stable), never on `title` or `detail` (human-readable, may change). Retrying is safe
whenever the request carries the same `Idempotency-Key`: a retry of a completed request replays the original response
with the header `Idempotent-Replayed: true`.

The public API is the **gateway** (`/api/v1/**` on port 8080). The inventory service's own REST API is internal (used by
its tests and for direct operations) and returns the same types where they overlap.

## Request problems (4xx — fix the request)

### validation-failed
**400.** Fields or parameters are invalid. `violations` lists each one:
```json
"violations": [{"field": "quantity", "message": "must be less than or equal to 100"}]
```
Domain rules that cannot be expressed as field constraints (e.g. more decimal places than the currency allows) return
this type with a `detail` and no `violations`.

### malformed-request
**400.** The body is missing, not JSON, has a value of the wrong type, or contains a field the endpoint does not
accept. Unknown fields are rejected rather than ignored, so typos and mass-assignment attempts fail loudly (OWASP API3).

### missing-idempotency-key
**400.** A state-changing `POST` (reservation, order, stock adjustment) has no `Idempotency-Key` header. Use a new
random value (e.g. a UUID) per operation and the *same* value when retrying it. Keys are 1–128 printable ASCII
characters without spaces.

### invalid-parameter · invalid-cursor
**400** (inventory API). A path or query parameter has the wrong format, or a `cursor` was not produced by this API.
Cursors are opaque: pass `nextCursor` from the previous page unchanged. The gateway reports both as
`validation-failed`.

### payment-simulation-disabled
**400.** The `X-Payment-Simulation` header chooses the simulated payment outcome and is only accepted where the
operator enabled it (local stack, tests). Omit the header.

### request-rejected
**400.** A downstream service rejected the request without naming a more specific problem type. `detail` carries
its explanation.

### unauthorized
**401.** No bearer token, or the token is invalid: bad signature, expired, wrong issuer or audience, or a subject that
is not a user id. The response carries `WWW-Authenticate: Bearer`.

### forbidden
**403.** The token is valid but lacks the role for this operation (administration needs `ADMIN`).

### product-not-found · reservation-not-found · order-not-found · not-found
**404.** No such object — or it belongs to another user. Other users' reservations and orders are reported as absent,
never as forbidden, so ids cannot be probed (OWASP API1).

### sku-already-exists
**409.** Another product uses this SKU. Concurrent creates of one SKU produce exactly one product.

### insufficient-stock
**409.** Not enough units are available for this reservation. Availability is shown by
`GET /api/v1/products/{id}/availability`.

### insufficient-available-stock
**409.** A stock adjustment would take available stock below zero (units that are reserved or sold cannot be removed).

### invalid-reservation-state
**409.** The reservation is not `PENDING` (already cancelled, confirmed or expired), so it cannot be cancelled.

### reservation-not-orderable · reservation-already-ordered
**409.** An order needs a `PENDING`, unexpired reservation owned by the caller, and a reservation can be ordered
only once. A retry of the *same* order request with the same `Idempotency-Key` returns the original order instead.

### optimistic-lock-conflict
**409.** Only with the optimistic-locking strategy: the product was too contended and the retries ran out. Retry with
the same `Idempotency-Key`.

### purchase-limit-exceeded
**422.** The user would hold more units of this product than allowed (pending + confirmed; 2 by default). Units return
to the allowance when a reservation is cancelled or expires.

### idempotency-key-reused
**422.** This `Idempotency-Key` was already used for a *different* request (different body or endpoint). Generate a
new key for a new operation.

### rate-limit-exceeded
**429.** The user sent more write requests than their budget allows. `Retry-After` says when a token is available.
This is about the client's own rate; compare `service-unavailable`.

## Server problems (5xx — retry later)

### service-unavailable
**503.** The system is protecting itself or a dependency is down: a circuit breaker is open, a bulkhead is full, the
database is unreachable, or a product is so contended that the request could not lock its stock within 2 s. `Retry-After` says when to retry; retrying with the same `Idempotency-Key` is safe.

### gateway-timeout
**504.** A downstream service did not answer within the gateway's deadline (2 s). The operation may or may not have
happened — retry with the same `Idempotency-Key` to get its result.

### internal-error
**500.** An unexpected error. Details are logged with the `traceId` and deliberately not returned.
