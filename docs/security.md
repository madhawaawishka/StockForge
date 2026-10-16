# Security review — OWASP API Security Top 10 (2023)

Review of the public API (the gateway) and the services behind it, done for W6-01 (spec §28). For each risk: what an
attack looks like here, the controls in place, and the test that keeps them in place. Gaps are listed at the end
rather than hidden.

Scope: the gateway's REST API (`/api/v1/**`), the internal gRPC APIs, Kafka messages, the container images and the
CI pipeline. Authentication *issuance* is out of scope — there is no identity provider in the project (ADR-011).

## API1 — Broken object level authorization
*Attack:* a shopper requests `GET /api/v1/orders/{id}` or cancels a reservation with someone else's id.
- The owner is always the token's subject; request bodies have no user-id field the gateway would trust.
- The gateway forwards the caller as `requester_user_id`; the **owning service** compares it with the object's owner
  and answers *not found* on a mismatch — never *forbidden*, so ids cannot be probed. Defence in depth: a bug in the
  gateway cannot leak an order, because the order service checks again.
- Operators (role `ADMIN`) are passed without a requester and may read any object.

*Tests:* `GatewayApiIT.anotherUsersReservationIsNotFound`, `GatewayApiIT.theOwnerIsAlwaysTheTokenSubjectNeverTheBody`,
`OrderQueriesTest`, `PlaceOrderUseCaseTest.anotherUsersReservationCannotBeOrdered`,
`ReservationLifecycleTest.anotherUsersReservationLooksLikeItDoesNotExist`, `InventoryGrpcServiceTest`, and the
deployed-stack check in `load-tests/smoke.js`.

## API2 — Broken authentication
- Spring Security resource server; HS256 bearer tokens validated for signature, expiry (with clock-skew default),
  issuer, audience and a subject that must be a UUID. Tokens with `alg: none` or another algorithm are rejected.
- The signing key comes only from the environment, must be at least 32 bytes, and has **no default**: the gateway
  refuses to start without it (`GatewayProperties`). Local and test keys are labelled as such.
- Stateless: no sessions, no cookies, so no session fixation and no CSRF surface (CSRF protection is disabled
  deliberately for that reason).
- 401 responses are RFC 9457 problems with `WWW-Authenticate: Bearer`; they do not say *why* a token was rejected.

*Tests:* `GatewayApiIT.writesWithoutATokenAreUnauthorizedProblems`, `…forgedExpiredOrMisaddressedTokensAreRejected`.

## API3 — Broken object property level authorization
*Attack:* mass assignment (`{"productId": …, "userId": "<victim>", "status": "CONFIRMED"}`) or reading fields that
should stay internal.
- Request DTOs are records with exactly the accepted fields; Jackson is configured with
  `fail-on-unknown-properties: true`, so an extra field is a 400 `malformed-request`, not silently ignored.
- Responses are explicit view records (`Views.*`); internal columns (versions, idempotency hashes, saga internals that
  are not meant for clients) are never serialized by accident.

*Tests:* `GatewayApiIT.theOwnerIsAlwaysTheTokenSubjectNeverTheBody`,
`ProductControllerTest.unknownJsonFieldsAreRejectedInsteadOfIgnored`.

## API4 — Unrestricted resource consumption
- Per-user token buckets on the write endpoints (Redis, atomic Lua script), 429 + `Retry-After`.
- Bulkheads cap in-flight calls per downstream service (200 / 100); excess is shed with 503 + `Retry-After`.
- Every remote call has a deadline (gateway → services 2 s); database statement (5 s), lock (2 s), idle-in-transaction
  (10 s) and socket timeouts; Redis 100 ms; Kafka producer `max.block.ms` 2 s.
- Bounded inputs: quantity 1–100, page size ≤ 100, keyset pagination (no deep `OFFSET`), idempotency key ≤ 128
  printable ASCII, DLT replay ≤ 1,000 messages per call.
- Containers have memory and CPU limits; the JVM exits on `OutOfMemoryError` instead of limping on.

*Tests:* `GatewayApiIT.aUserBurstingPastTheLimitGets429WithRetryAfter`, `ResilientGrpcCallTest`; input bounds are Bean
Validation constraints on the controllers' request records; behaviour under overload is measured in
`docs/benchmarks/platform-vs-virtual-threads.md`.

## API5 — Broken function level authorization
- `/api/v1/admin/**` requires `ROLE_ADMIN` (from the token's `roles` claim); everything outside the documented routes is
  `denyAll()`.
- Admin use cases check the role again in the application layer (`AdminUseCases`), so a routing mistake cannot expose
  them.
- Admin actions (product creation, stock adjustment, dead-letter replay) are written to append-only audit tables
  (a trigger rejects `UPDATE`/`DELETE`).

*Tests:* `GatewayApiIT.administrationNeedsTheAdminRole`,
`GatewayUseCasesTest.adminOperationsRequireTheAdminRoleEvenIfRoutingLetARequestThrough`.

## API6 — Unrestricted access to sensitive business flows
*Attack:* a bot reserves the whole stock of a hot product and never pays (inventory denial), or replays requests.
- Purchase limit per user and product (2 by default), enforced in the domain **and** by a database CHECK constraint.
- Reservations expire after 10 minutes and return stock automatically; the expiry worker keeps up with ≈ 700/s.
- Rate limits on reservations and orders; mandatory `Idempotency-Key` so retries never double-book.
- Payment outcome simulation (`X-Payment-Simulation`) is disabled unless the operator enables it.

*Tests:* `PurchaseLimitWriteSkewIT`, `ReserveStockUseCaseTest`, `ReliableMessagingIT.anExpiredReservationIsReleased…`,
`GatewayApiIT.ordersAreAcceptedAsynchronouslyAndSimulationIsOffByDefault`.

## API7 — Server-side request forgery
No endpoint fetches a URL supplied by a client. Service addresses are configuration. The only "target" a client can
name is a dead-letter topic for replay, which is checked against a fixed allow-list in the order service and a
`[a-z-]{1,64}` pattern at the gateway.

## API8 — Security misconfiguration
- **Only the gateway is exposed.** Compose publishes only the gateway's API port; internal services, Kafka and Redis
  ports used by containers stay on the compose network. In AWS only the ALB is public (infra/terraform).
- The gateway's actuator (health, metrics) runs on a separate management port (8180) that is not published.
- API documentation (OpenAPI, Swagger UI) is off unless enabled (`*_API_DOCS_ENABLED`).
- Errors never contain stack traces or exception messages from libraries; unexpected errors are logged with a trace id
  and answered with a generic `internal-error`.
- Containers: non-root user (uid 10001, no shell), read-only root filesystem with a `/tmp` tmpfs,
  `no-new-privileges`, all Linux capabilities dropped, JRE-only runtime image.
- Spring Security's default headers (`X-Content-Type-Options`, `Cache-Control`, `X-Frame-Options`); HSTS applies once
  TLS terminates at the load balancer.
- Database: one role per service that can reach only its own schema (ADR-002); `public` schema revoked.

*Checks:* Trivy misconfiguration scan of the Dockerfile in CI (0 findings at MEDIUM or above on 2026-10-14). API docs
being off without the opt-in variable was verified by running the service with no profile (session log, 2026-10-09);
there is no automated test for it yet.

## API9 — Improper inventory management
- One versioned public API (`/api/v1`), one OpenAPI document generated from the code; internal APIs are gRPC with
  contracts in `libs/proto`, linted and checked for breaking changes by `buf` in CI.
- No forgotten endpoints: the inventory service's REST controllers are internal (not routed by the gateway, not
  published by compose); the order service has no REST API at all.
- Every dependency version is pinned in Maven (BOM-managed), every image by version; Dependabot proposes updates.

## API10 — Unsafe consumption of APIs
- Messages from Kafka are untrusted input: envelopes are decoded strictly; undecodable or unknown messages are poison
  and go to the dead-letter topic instead of being half-processed; the inbox makes duplicates harmless.
- gRPC answers from other services are mapped through explicit status → problem-type tables; unknown statuses become
  a generic error, never a pass-through of the downstream's message.
- The payment provider is called with a timeout outside any database transaction; a lost answer leaves the payment
  pending and the saga's timeout resolves it.

## Supply chain and pipeline
| Control | Where |
| :--- | :--- |
| Secret scanning of the full history | gitleaks job in `ci.yml` |
| Dependency, secret and IaC scanning, results in GitHub code scanning | Trivy `fs` job; fails on CRITICAL |
| Container image scanning | Trivy `image` on all five images in the `system` job; fails on CRITICAL |
| Static analysis on every compile | Error Prone, warnings are errors |
| Contract compatibility | `buf lint` / `buf breaking` |
| Least-privilege workflow tokens | `permissions: contents: read` by default; script-injection-safe inputs in `load-test.yml` |
| Dependency updates | Dependabot (Maven, GitHub Actions, Docker Compose, Dockerfile base images, Terraform) |
| Workflow and script linting | `actionlint` (includes shellcheck of every `run:` step), `shellcheck` of `scripts/` |

### Image scan, 2026-10-16
A Trivy scan of the five images (built on Spring Boot 4.1.1 and Temurin 21.0.8) found, in every image:

| Finding | Severity | Fix |
| :--- | :--- | :--- |
| CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 in `tomcat-embed-core` 11.0.24 | CRITICAL | `tomcat.version` 11.0.25 |
| five CVEs in `tools.jackson.core` 3.1.5 and `com.fasterxml.jackson.core` 2.21.5 | HIGH | `jackson-bom.version` 3.1.7, `jackson-2-bom.version` 2.21.7 |
| CVE-2026-106451 in `at.yawk.lz4:lz4-java` 1.10.1 (via kafka-clients) | HIGH | managed at 1.11.4 |
| OpenSSL and GnuPG packages of the Ubuntu base image | HIGH | base image `eclipse-temurin:21.0.12.1_1` |

Spring Boot 4.1.1 was the newest release, so the library fixes are overrides of Boot's version properties in the
parent `pom.xml`, each commented with the reason and removable once Boot ships the version. Without them the CI image
scan, which fails on fixable CRITICAL findings, would have failed the first build. After the fixes, each image has
**0 CRITICAL and 0 HIGH** findings; 20 MEDIUM (2 of them with a fix available) and 4 LOW remain, reported in GitHub code
scanning.

## Known gaps and next steps
- **Service-to-service traffic is not encrypted** inside the private network (gRPC plaintext). Next: mTLS (e.g. a
  service mesh or Spring gRPC's TLS support with per-service certificates).
- **HS256 shared key.** A real deployment should validate RS256/ES256 tokens from an identity provider's JWKS endpoint;
  key rotation then needs no coordinated restart.
- **No WAF / bot management** in front of the ALB; rate limits are per user, not per IP or device fingerprint, so
  many accounts can still add up.
- **Fail-open rate limiting** (ADR-010) trades abuse protection for availability during a Redis outage — a conscious
  choice with an alert, but a choice.
- **Image vulnerabilities** depend on the base image and dependencies at build time; CI fails only on CRITICAL with a
  fix available. HIGH findings are reported in code scanning but do not block.
- The local stack's Grafana allows anonymous admin access (image default) — acceptable only because it is local.
