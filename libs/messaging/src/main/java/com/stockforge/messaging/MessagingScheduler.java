package com.stockforge.messaging;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the outbox relay and the retention job on a dedicated thread.
 *
 * <p>A lifecycle bean rather than {@code @Scheduled}, so graceful shutdown is explicit (spec §33): on stop no new run
 * starts, and the batch in flight is allowed to finish before the Kafka producer and the connection pool close.
 */
final class MessagingScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MessagingScheduler.class);
    private static final Duration FAILURE_LOG_INTERVAL = Duration.ofSeconds(30);

    private final OutboxRelay relay;
    private final MessagingRetention retention;
    private final MessagingProperties.Outbox config;

    private ScheduledExecutorService executor;
    private volatile boolean running;
    private long lastFailureLogNanos;

    /** @param relay {@code null} when this instance does not relay (for example, a consume-only service) */
    MessagingScheduler(OutboxRelay relay, MessagingRetention retention, MessagingProperties.Outbox config) {
        this.relay = relay;
        this.retention = retention;
        this.config = config;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "messaging-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        if (relay != null) {
            ScheduledFuture<?> unused = executor.scheduleWithFixedDelay(
                    this::relayUntilDrained, 0, config.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
        ScheduledFuture<?> unused = executor.scheduleWithFixedDelay(
                this::purgeExpired,
                config.cleanupInterval().toMillis(),
                config.cleanupInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(config.sendTimeout().toMillis() + 1_000, TimeUnit.MILLISECONDS)) {
                log.warn("Messaging scheduler did not finish its current batch in time; unpublished rows stay pending");
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Stop before the Kafka listener containers (phase MAX_VALUE - 100) and long before the pools close.
        return Integer.MAX_VALUE - 50;
    }

    /** A full batch means more rows are waiting: publish again right away instead of sleeping (drains backlogs). */
    private void relayUntilDrained() {
        try {
            int published;
            do {
                published = relay.publishPendingBatch();
            } while (running && published == config.batchSize());
            lastFailureLogNanos = 0;
        } catch (RuntimeException failure) {
            // Never let an exception escape: it would silently cancel all future runs of this task.
            logThrottled("Outbox relay run failed (database or broker unavailable?)", failure);
        }
    }

    private void purgeExpired() {
        try {
            int deleted = retention.purge();
            if (deleted > 0) {
                log.info("Retention job deleted {} published outbox / processed inbox rows", deleted);
            }
        } catch (RuntimeException failure) {
            log.warn("Retention job failed; it runs again in {}", config.cleanupInterval(), failure);
        }
    }

    private void logThrottled(String message, RuntimeException failure) {
        long now = System.nanoTime();
        if (lastFailureLogNanos == 0 || now - lastFailureLogNanos > FAILURE_LOG_INTERVAL.toNanos()) {
            lastFailureLogNanos = now;
            log.warn("{}: {}", message, failure.toString());
        }
    }
}
