package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.port.IdGenerator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Predictable, increasing ids (…0001, …0002, …) so tests can assert on exact values and ordering. */
public class SequentialIdGenerator implements IdGenerator {

    private final AtomicLong next = new AtomicLong(1);

    @Override
    public UUID newId() {
        return new UUID(0, next.getAndIncrement());
    }
}
