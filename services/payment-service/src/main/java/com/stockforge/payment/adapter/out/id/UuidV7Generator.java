package com.stockforge.payment.adapter.out.id;

import com.stockforge.payment.domain.model.PaymentId;
import com.stockforge.payment.domain.port.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** Time-ordered UUIDv7 payment ids (RFC 9562, ADR-005). */
public final class UuidV7Generator implements IdGenerator {

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public PaymentId newPaymentId() {
        long timestamp = clock.millis() & 0xFFFF_FFFF_FFFFL;
        long mostSignificant = (timestamp << 16) | (0x7L << 12) | (random.nextLong() & 0x0FFFL);
        long leastSignificant = 0x8000_0000_0000_0000L | (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL);
        return PaymentId.of(new UUID(mostSignificant, leastSignificant));
    }
}
