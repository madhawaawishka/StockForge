package com.stockforge.order.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockforge.events.DomainEvents;
import com.stockforge.order.application.ProcessPaymentOutcomeUseCase;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes payment events to advance or compensate the checkout saga (spec §16 B, §16 C). */
@Component
class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private static final String CONSUMER_GROUP = "order-service-payment-group";

    private final ProcessPaymentOutcomeUseCase processPaymentOutcomeUseCase;
    private final InboxDeduplicator inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    PaymentEventConsumer(
            ProcessPaymentOutcomeUseCase processPaymentOutcomeUseCase,
            InboxDeduplicator inbox,
            ObjectMapper objectMapper,
            Clock clock) {
        this.processPaymentOutcomeUseCase = processPaymentOutcomeUseCase;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @KafkaListener(topics = DomainEvents.TOPIC_PAYMENT_EVENTS, groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        extractTraceContext(record);
        try {
            String eventId =
                    record.key() != null ? record.key() : UUID.randomUUID().toString();
            Instant now = clock.instant();

            boolean isNew = inbox.tryAcquire(CONSUMER_GROUP, eventId, now);
            if (!isNew) {
                log.info("Duplicate payment event ignored for key: {}", eventId);
                return;
            }

            JsonNode root = objectMapper.readTree(record.value());
            if (!root.has("orderId")) {
                log.warn("Payment event missing orderId: {}", record.value());
                return;
            }

            UUID orderId = UUID.fromString(root.get("orderId").asText());
            UUID paymentId = root.has("paymentId")
                    ? UUID.fromString(root.get("paymentId").asText())
                    : UUID.randomUUID();

            if (root.has("amount") && !root.has("refunded")) {
                // PaymentSucceeded
                log.info("Processing PaymentSucceeded for order {}", orderId);
                processPaymentOutcomeUseCase.handlePaymentSuccess(orderId, paymentId);
            } else if (root.has("reason")) {
                // PaymentFailed
                String reason = root.get("reason").asText();
                log.info("Processing PaymentFailed for order {}: {}", orderId, reason);
                processPaymentOutcomeUseCase.handlePaymentFailure(orderId, reason);
            } else if (root.has("refunded") || record.value().contains("Refunded")) {
                // PaymentRefunded
                log.info("Processing PaymentRefunded for order {}", orderId);
                processPaymentOutcomeUseCase.handlePaymentRefunded(orderId);
            } else {
                // PaymentTimedOut
                log.info("Processing PaymentTimedOut for order {}", orderId);
                processPaymentOutcomeUseCase.handlePaymentTimedOut(orderId);
            }
        } catch (Exception e) {
            log.error("Failed handling payment event: {}", e.getMessage(), e);
            throw new IllegalStateException("Error processing payment event", e);
        } finally {
            clearTraceContext();
        }
    }

    private void extractTraceContext(ConsumerRecord<?, ?> record) {
        if (record.headers() != null) {
            org.apache.kafka.common.header.Header header = record.headers().lastHeader("traceparent");
            if (header != null && header.value() != null) {
                String tp = new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
                if (tp.length() >= 53 && tp.charAt(2) == '-' && tp.charAt(35) == '-') {
                    String traceId = tp.substring(3, 35);
                    int nextDash = tp.indexOf('-', 36);
                    if (nextDash > 36) {
                        String spanId = tp.substring(36, nextDash);
                        org.slf4j.MDC.put("traceId", traceId);
                        org.slf4j.MDC.put("spanId", spanId);
                    }
                }
            }
        }
    }

    private void clearTraceContext() {
        org.slf4j.MDC.remove("traceId");
        org.slf4j.MDC.remove("spanId");
    }
}
