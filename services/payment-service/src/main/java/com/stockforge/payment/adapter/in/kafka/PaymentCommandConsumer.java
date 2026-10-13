package com.stockforge.payment.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockforge.events.DomainEvents;
import com.stockforge.payment.application.ProcessPaymentCommand;
import com.stockforge.payment.application.ProcessPaymentUseCase;
import com.stockforge.payment.application.RefundPaymentUseCase;
import com.stockforge.payment.domain.model.OrderId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Inbound Kafka consumer for payment commands with idempotent inbox deduplication (spec §16, §18). */
@Component
class PaymentCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandConsumer.class);
    private static final String CONSUMER_GROUP = "payment-service-group";

    private final ProcessPaymentUseCase processPaymentUseCase;
    private final RefundPaymentUseCase refundPaymentUseCase;
    private final InboxDeduplicator inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    PaymentCommandConsumer(
            ProcessPaymentUseCase processPaymentUseCase,
            RefundPaymentUseCase refundPaymentUseCase,
            InboxDeduplicator inbox,
            ObjectMapper objectMapper,
            Clock clock) {
        this.processPaymentUseCase = processPaymentUseCase;
        this.refundPaymentUseCase = refundPaymentUseCase;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @KafkaListener(topics = DomainEvents.TOPIC_PAYMENT_COMMANDS, groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        extractTraceContext(record);
        try {
            String eventId =
                    record.key() != null ? record.key() : UUID.randomUUID().toString();
            Instant now = clock.instant();

            boolean isNew = inbox.tryAcquire(CONSUMER_GROUP, eventId, now);
            if (!isNew) {
                log.info("Duplicate payment command ignored for key: {}", eventId);
                return;
            }

            JsonNode node = objectMapper.readTree(record.value());
            if (node.has("amount") && node.has("orderId")) {
                // RequestPaymentCommand
                UUID orderId = UUID.fromString(node.get("orderId").asText());
                UUID userId = UUID.fromString(node.get("userId").asText());
                BigDecimal amount = new BigDecimal(node.get("amount").asText());
                String currency = node.get("currency").asText();
                String idempotencyKey =
                        node.has("idempotencyKey") ? node.get("idempotencyKey").asText() : "";
                String simulationOutcome = node.has("simulationOutcome")
                        ? node.get("simulationOutcome").asText()
                        : "SUCCEEDED";

                ProcessPaymentCommand command =
                        new ProcessPaymentCommand(orderId, userId, amount, currency, idempotencyKey, simulationOutcome);
                processPaymentUseCase.execute(command);
            } else if (node.has("reason") && node.has("orderId")) {
                // RefundPaymentCommand
                UUID orderId = UUID.fromString(node.get("orderId").asText());
                String reason = node.get("reason").asText();
                refundPaymentUseCase.execute(OrderId.of(orderId), reason);
            }
        } catch (Exception e) {
            log.error("Failed processing payment command from partition {}: {}", record.partition(), e.getMessage(), e);
            throw new IllegalStateException("Error handling payment command", e);
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
