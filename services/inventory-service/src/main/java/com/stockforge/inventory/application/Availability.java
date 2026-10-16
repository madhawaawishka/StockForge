package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Inventory;
import java.util.Objects;

/**
 * Stock levels as shown to shoppers.
 *
 * @param approximate {@code true} when served from the short-lived cache (spec §17: availability is eventually
 *     consistent and labelled approximate; the reservation path always re-checks the database)
 */
public record Availability(Inventory inventory, boolean approximate) {

    public Availability {
        Objects.requireNonNull(inventory, "inventory must not be null");
    }
}
