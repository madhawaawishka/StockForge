# ADR-011: JWT authentication at the gateway, object-level authorization in the owning service
- Status: Accepted
- Date: 2026-10-13

## Context
Shoppers may only see and change their own reservations and orders; operators manage the catalog and stock. Broken
object-level authorization (BOLA, OWASP API1) is the most common API vulnerability: an endpoint that checks "is the
caller logged in?" but not "does the caller own object 42?" (spec §28).

## Decision
- **Authentication** happens once, at the gateway: Spring Security resource server, bearer JWTs validated for
  signature (HS256 with a ≥ 32-byte key from the environment; no default), expiry, issuer, audience, and a subject
  that must be a UUID. Roles come from the `roles` claim; `ADMIN` is required under `/api/v1/admin/**`. Everything
  outside the documented routes is denied.
- **Object-level authorization** is enforced by the service that owns the object, not by the gateway: the gateway
  forwards the caller as `requester_user_id` on every gRPC call, and inventory/order compare it with the object's
  owner. A mismatch answers *not found* — never *forbidden* — so object ids cannot be probed. Operators are passed
  without a requester and may read any object.
- Internal services are not exposed publicly (compose publishes only the gateway; the gateway's own actuator runs on a
  separate management port).

## Consequences
- An authorization bug in the gateway cannot leak another user's order, because the owning service checks again.
- Key rotation for HS256 needs a coordinated restart. A real identity provider would switch the resource server to
  asymmetric keys from a JWKS endpoint; that is configuration, not code.
- Service-to-service gRPC is plaintext inside the private network in this project; mTLS is listed as future work in
  `docs/security.md`.

## Evidence
- `GatewayApiIT`: 401 without a token and for forged, expired or misaddressed tokens; 403 for non-admins on admin
  routes; the owner is always the token subject, never a body field; 404 for another user's reservation.
- Owning services: `ReservationLifecycleTest` and `InventoryGrpcServiceTest` (reservations), `OrderQueriesTest` and
  `PlaceOrderUseCaseTest` (orders) — non-owners get "not found", operators see everything.
- `load-tests/smoke.js` re-checks 401 and BOLA on orders against every deployed stack.
