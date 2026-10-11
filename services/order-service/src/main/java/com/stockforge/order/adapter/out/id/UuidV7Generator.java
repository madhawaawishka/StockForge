package com.stockforge.order.adapter.out.id;

import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.port.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

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
    public OrderId newOrderId() {
        long timestamp = clock.millis() & TIMESTAMP_MASK;
        long mostSignificant = (timestamp << 16) | VERSION_7 | (random.nextLong() & RAND_A_MASK);
        long leastSignificant = VARIANT_RFC_9562 | (random.nextLong() & RAND_B_MASK);
        return OrderId.of(new UUID(mostSignificant, leastSignificant));
    }
}
