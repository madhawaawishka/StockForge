package com.stockforge.events;

/**
 * Kafka topics shared between services (spec §18).
 *
 * <p>Events are facts in the past tense that any service may consume; commands are requests addressed to the single
 * service that owns the data. Every message is keyed by the id of the aggregate it concerns, so all messages of one
 * aggregate land on one partition and are processed in order (ADR-010).
 */
public final class Topics {

    /** Facts about reservations, published by Inventory. */
    public static final String INVENTORY_EVENTS = "inventory-events";

    /** Requests to Inventory (confirm or release a reservation), published by the Order saga. */
    public static final String INVENTORY_COMMANDS = "inventory-commands";

    /** Facts about orders, published by Order. */
    public static final String ORDER_EVENTS = "order-events";

    /** Requests to Payment (charge or refund), published by the Order saga. */
    public static final String PAYMENT_COMMANDS = "payment-commands";

    /** Facts about payments, published by Payment. */
    public static final String PAYMENT_EVENTS = "payment-events";

    /** Suffix of the dead-letter topic that receives messages a consumer could not process (spec §22). */
    public static final String DEAD_LETTER_SUFFIX = ".DLT";

    private Topics() {}

    public static String deadLetterTopicOf(String topic) {
        return topic + DEAD_LETTER_SUFFIX;
    }
}
