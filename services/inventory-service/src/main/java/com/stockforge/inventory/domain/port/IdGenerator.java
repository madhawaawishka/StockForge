package com.stockforge.inventory.domain.port;

import java.util.UUID;

/**
 * Generates identifiers for new aggregates. A port so that production uses time-ordered UUIDv7s while tests use
 * predictable values.
 */
public interface IdGenerator {

    UUID newId();
}
