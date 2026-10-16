package com.stockforge.messaging;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** UUIDv7 (RFC 9562) event ids: time-ordered for index locality, with 74 random bits (ADR-005). */
final class TimeOrderedIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TimeOrderedIds() {}

    static UUID next(Clock clock) {
        long timestamp = clock.millis() & 0xFFFF_FFFF_FFFFL;
        long mostSignificant = (timestamp << 16) | (0x7L << 12) | (RANDOM.nextLong() & 0x0FFFL);
        long leastSignificant = 0x8000_0000_0000_0000L | (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL);
        return new UUID(mostSignificant, leastSignificant);
    }
}
