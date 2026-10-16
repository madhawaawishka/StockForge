package com.stockforge.messaging;

import com.stockforge.events.MessageHeaders;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes pending outbox rows to Kafka (spec §20).
 *
 * <p>Guarantees and their mechanisms (ADR-008):
 *
 * <ul>
 *   <li><b>No lost events:</b> a row is marked PUBLISHED only after the broker acknowledged it ({@code acks=all}). A
 *       crash in between re-sends the row; consumers deduplicate by event id.
 *   <li><b>Per-aggregate order:</b> rows are read in insertion order ({@code seq}); only one relay per service runs at
 *       a time (a transaction-scoped advisory lock), and rows are marked published only up to the first failed send, so
 *       a failed event is never overtaken by a later one.
 *   <li><b>Several instances are safe:</b> the instance that does not get the lock skips the run.
 * </ul>
 *
 * <p>The row locks are held while publishing. That is acceptable because only relays contend for outbox rows; user
 * requests never wait on them (spec §20). The send timeout bounds how long that can take.
 */
public final class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 2_000;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final KafkaTemplate<String, String> kafka;
    private final EnvelopeCodec codec;
    private final TraceContextSupport tracing;
    private final MessagingProperties.Outbox config;
    private final OutboxMetrics metrics;

    OutboxRelay(
            JdbcClient jdbc,
            TransactionTemplate transaction,
            KafkaTemplate<String, String> kafka,
            EnvelopeCodec codec,
            TraceContextSupport tracing,
            MessagingProperties.Outbox config,
            OutboxMetrics metrics) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.kafka = kafka;
        this.codec = codec;
        this.tracing = tracing;
        this.config = config;
        this.metrics = metrics;
    }

    /** Publishes at most one batch; returns how many events were published. */
    public int publishPendingBatch() {
        Integer published = transaction.execute(status -> publishInTransaction());
        return published == null ? 0 : published;
    }

    private int publishInTransaction() {
        if (!acquireRelayLock()) {
            return 0;
        }
        metrics.recordBacklog(jdbc);

        List<PendingEvent> batch = jdbc.sql("""
                        SELECT id, topic, aggregate_id, event_type, payload::text AS payload, headers::text AS headers
                        FROM outbox_events
                        WHERE status = 'PENDING'
                        ORDER BY seq
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", config.batchSize())
                .query((rs, rowNum) -> new PendingEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("headers")))
                .list();
        if (batch.isEmpty()) {
            return 0;
        }

        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(batch.size());
        for (PendingEvent event : batch) {
            sends.add(send(event));
        }

        List<UUID> acknowledged = new ArrayList<>(batch.size());
        long deadline = System.nanoTime() + config.sendTimeout().toNanos();
        for (int i = 0; i < batch.size(); i++) {
            PendingEvent event = batch.get(i);
            try {
                sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                acknowledged.add(event.id());
            } catch (ExecutionException failed) {
                recordFailure(event, failed.getCause() != null ? failed.getCause() : failed);
                break;
            } catch (TimeoutException timedOut) {
                recordFailure(event, timedOut);
                break;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!acknowledged.isEmpty()) {
            // clock_timestamp(), not now(): now() is the start of this transaction, which began before the sends.
            List<Double> lagSeconds =
                    jdbc.sql("""
                            UPDATE outbox_events
                            SET status = 'PUBLISHED', published_at = clock_timestamp()
                            WHERE id IN (:ids)
                            RETURNING EXTRACT(EPOCH FROM published_at - created_at)::float8 AS lag
                            """).param("ids", acknowledged).query(Double.class).list();
            lagSeconds.forEach(metrics::recordPublishLag);
        }
        metrics.recordPublished(acknowledged.size());
        return acknowledged.size();
    }

    private boolean acquireRelayLock() {
        // Transaction-scoped: released automatically on commit or rollback, so a crashed relay never blocks others.
        // The schema name makes the key unique per service on the shared PostgreSQL instance.
        Boolean acquired = jdbc.sql("""
                        SELECT pg_try_advisory_xact_lock(hashtext('stockforge.outbox-relay.' || current_schema()))
                        """).query(Boolean.class).single();
        return Boolean.TRUE.equals(acquired);
    }

    private CompletableFuture<SendResult<String, String>> send(PendingEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.topic(), event.aggregateId(), event.envelopeJson());
        record.headers().add(MessageHeaders.EVENT_ID, bytes(event.id().toString()));
        record.headers().add(MessageHeaders.EVENT_TYPE, bytes(event.eventType()));

        Map<String, String> traceHeaders = codec.decodeHeaders(event.headersJson());
        Span span = tracing.startChildOf(traceHeaders, "outbox publish " + event.eventType());
        if (span == null) {
            // Tracing is off in this service: forward the stored context so downstream traces still connect.
            traceHeaders.forEach((name, value) -> record.headers().add(name, bytes(value)));
            return sendSafely(record);
        }
        Tracer.SpanInScope scope = tracing.activate(span);
        try {
            // The Kafka producer observation becomes a child of this span and writes its own traceparent header.
            return sendSafely(record);
        } finally {
            scope.close();
            span.end();
        }
    }

    private CompletableFuture<SendResult<String, String>> sendSafely(ProducerRecord<String, String> record) {
        try {
            return kafka.send(record);
        } catch (RuntimeException unavailable) {
            // For example, the producer could not fetch topic metadata within max.block.ms.
            return CompletableFuture.failedFuture(unavailable);
        }
    }

    private void recordFailure(PendingEvent event, Throwable cause) {
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        log.warn("Publishing outbox event {} ({}) failed; it stays pending: {}", event.id(), event.eventType(), error);
        jdbc.sql("""
                        UPDATE outbox_events
                        SET attempts = attempts + 1, last_error = :error
                        WHERE id = :id
                        """)
                .param("id", event.id())
                .param("error", error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error)
                .update();
        metrics.recordFailure();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record PendingEvent(
            UUID id, String topic, String aggregateId, String eventType, String envelopeJson, String headersJson) {}
}
