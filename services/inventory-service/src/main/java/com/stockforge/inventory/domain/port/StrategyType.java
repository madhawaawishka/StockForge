package com.stockforge.inventory.domain.port;

/** Supported inventory reservation concurrency control strategies (spec §14). */
public enum StrategyType {
    /** Single conditional UPDATE with WHERE available_quantity >= :qty (recommended baseline). */
    ATOMIC_CONDITIONAL_UPDATE,

    /** Explicit row lock with SELECT ... FOR UPDATE. */
    PESSIMISTIC_LOCKING,

    /** Version column check with bounded application retries. */
    OPTIMISTIC_LOCKING,

    /** Read-then-write without locking or version check (test-only; demonstrates overselling). */
    NAIVE
}
