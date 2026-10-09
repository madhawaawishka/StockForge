package com.stockforge.inventory.adapter.out.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30.123Z");

    @Test
    void producesVersion7Rfc9562Uuids() {
        UUID id = new UuidV7Generator(Clock.fixed(NOW, ZoneOffset.UTC)).newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheCurrentUnixTimeInMilliseconds() {
        UUID id = new UuidV7Generator(Clock.fixed(NOW, ZoneOffset.UTC)).newId();

        long embeddedMillis = id.getMostSignificantBits() >>> 16;
        assertThat(embeddedMillis).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void laterTimestampsSortAfterEarlierOnesInUnsignedByteOrder() {
        UUID earlier = new UuidV7Generator(Clock.fixed(NOW, ZoneOffset.UTC)).newId();
        UUID later = new UuidV7Generator(Clock.fixed(NOW.plusMillis(1), ZoneOffset.UTC)).newId();

        // PostgreSQL compares UUIDs as unsigned bytes; java.util.UUID#compareTo is signed, so compare explicitly.
        assertThat(Long.compareUnsigned(later.getMostSignificantBits(), earlier.getMostSignificantBits()))
                .isPositive();
    }

    @Test
    void idsGeneratedInTheSameMillisecondAreUnique() {
        UuidV7Generator generator = new UuidV7Generator(Clock.fixed(NOW, ZoneOffset.UTC));
        Set<UUID> ids = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.newId());
        }

        assertThat(ids).hasSize(10_000);
    }
}
