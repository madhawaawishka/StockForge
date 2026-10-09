# ADR-005: Application-generated UUIDv7 identifiers
- Status: Accepted
- Date: 2026-10-08

## Context
Public identifiers (products, reservations, orders) appear in URLs, so they must not be guessable or enumerable (spec §28). They are also primary keys of tables that receive heavy insert traffic, and they drive keyset pagination (spec §12, §13).

## Options considered
1. **`BIGSERIAL`** — compact and fast, but sequential IDs are enumerable and leak business volume.
2. **UUIDv4** — not guessable, but random values scatter inserts across the whole primary-key B-tree, causing page splits and poor cache locality.
3. **UUIDv7 (RFC 9562)** — 48-bit millisecond timestamp followed by random bits: time-ordered inserts with unguessable values.
4. **Generate in the database** (`DEFAULT uuidv7()`, available in PostgreSQL 18) vs **generate in the application**.

## Decision
UUIDv7, **generated in the application** by `UuidV7Generator` behind the `IdGenerator` port, with 74 random bits from `SecureRandom`.

Generating in the application means an aggregate has its identity before it is persisted, so the domain can build complete objects (and later, events referencing the ID) without a database round trip, and tests can inject predictable IDs.

## Consequences
- Inserts append near the right edge of the primary-key index; ordering by `id` approximates creation order, which gives keyset pagination a stable, meaningful order.
- IDs are 16 bytes instead of 8.
- PostgreSQL compares UUIDs as unsigned bytes while `java.util.UUID#compareTo` is signed; code and tests that sort IDs in Java must compare unsigned (see `InMemoryProductRepository`).
- Within one millisecond, order is random rather than strictly monotonic. Pagination only needs a stable total order, which UUID comparison provides.
