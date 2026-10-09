package com.stockforge.inventory.adapter.out.id;

import com.stockforge.inventory.domain.port.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Generates UUID version 7 values (RFC 9562): a 48-bit Unix timestamp in milliseconds followed by random bits.
 *
 * <p>Time ordering keeps primary-key B-tree inserts local (unlike random UUIDv4), and the 74 random bits come from
 * {@link SecureRandom} so public identifiers cannot be guessed or enumerated. See ADR-005.
 */
public final class UuidV7Generator implements IdGenerator {

    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;
    private static final long VERSION_7 = 0x7L << 12;
    private static final long RAND_A_MASK = 0x0FFFL;
    private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public UUID newId() {
        long timestamp = clock.millis() & TIMESTAMP_MASK;
        long mostSignificant = (timestamp << 16) | VERSION_7 | (random.nextLong() & RAND_A_MASK);
        long leastSignificant = VARIANT_RFC_9562 | (random.nextLong() & RAND_B_MASK);
        return new UUID(mostSignificant, leastSignificant);
    }
}
