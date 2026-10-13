package com.stockforge.inventory.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockforge.inventory.adapter.out.messaging.OutboxRelayWorker;
import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@InventoryIntegrationTest
class KafkaFailureChaosIT {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName(
            "Kafka outage causes outbox event to accumulate in PENDING state with last_error; recovers on reconnect")
    void kafkaOutageOutboxAccumulationAndDrain() {
        UUID eventId = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();

        // 1. Insert an outbox event directly in PENDING state
        jdbc.sql("""
                INSERT INTO inventory.outbox_events (id, aggregate_type, aggregate_id, event_type, payload, headers, status, attempts, created_at)
                VALUES (:id, 'Product', :aggId, 'StockReserved', '{"productId":"%s"}', '{"traceparent":"00-11-22-01"}', 'PENDING', 0, now())
                """.formatted(aggregateId))
                .param("id", eventId)
                .param("aggId", aggregateId)
                .update();

        // 2. Set up a KafkaTemplate that simulates outage (broker unreachable)
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> failingKafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(
                new KafkaException("Connection to Kafka broker timed out (simulated Toxiproxy cut)"));
        when(failingKafka.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        OutboxRelayWorker failingRelay =
                new OutboxRelayWorker(jdbc, failingKafka, objectMapper, Clock.systemUTC(), new SimpleMeterRegistry());

        // 3. Trigger relay execution during outage
        failingRelay.relayPendingEvents();

        // 4. Assert event was NOT lost: still PENDING, attempts incremented to 1, last_error recorded
        String status = jdbc.sql("SELECT status FROM inventory.outbox_events WHERE id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
        Integer attempts = jdbc.sql("SELECT attempts FROM inventory.outbox_events WHERE id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
        String lastError = jdbc.sql("SELECT last_error FROM inventory.outbox_events WHERE id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();

        assertThat(status).isEqualTo("PENDING");
        assertThat(attempts).isGreaterThanOrEqualTo(1);
        assertThat(lastError).contains("Connection to Kafka broker timed out");

        // 5. Simulate Kafka recovery
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> recoveredKafka = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(recoveredKafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        OutboxRelayWorker recoveredRelay =
                new OutboxRelayWorker(jdbc, recoveredKafka, objectMapper, Clock.systemUTC(), new SimpleMeterRegistry());

        // 6. Trigger relay execution after recovery
        recoveredRelay.relayPendingEvents();

        // 7. Assert event transitioned to PUBLISHED with published_at populated
        String updatedStatus = jdbc.sql("SELECT status FROM inventory.outbox_events WHERE id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
        assertThat(updatedStatus).isEqualTo("PUBLISHED");
    }
}
