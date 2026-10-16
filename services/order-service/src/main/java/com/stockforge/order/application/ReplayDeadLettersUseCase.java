package com.stockforge.order.application;

import com.stockforge.events.Topics;
import com.stockforge.order.domain.model.DomainValidationException;
import com.stockforge.order.domain.port.AuditLog;
import com.stockforge.order.domain.port.DeadLetterReplayer;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Administrator-triggered replay of dead-lettered messages (spec §12, §22, FR-10). Only the platform's own topics can
 * be replayed, at most a bounded number per call, and every replay is audited.
 *
 * <p>Replaying is safe because every consumer is idempotent (inbox + conditional transitions): a message that was in
 * fact applied before is skipped. The replay itself is a remote operation, so it runs without a database transaction;
 * the audit entry is a single insert.
 */
public class ReplayDeadLettersUseCase {

    public static final int MAX_MESSAGES_PER_CALL = 1_000;

    static final Set<String> REPLAYABLE_TOPICS = Set.of(
            Topics.INVENTORY_EVENTS,
            Topics.INVENTORY_COMMANDS,
            Topics.ORDER_EVENTS,
            Topics.PAYMENT_COMMANDS,
            Topics.PAYMENT_EVENTS);

    private final DeadLetterReplayer replayer;
    private final AuditLog auditLog;

    public ReplayDeadLettersUseCase(DeadLetterReplayer replayer, AuditLog auditLog) {
        this.replayer = Objects.requireNonNull(replayer, "replayer must not be null");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
    }

    public int execute(String topic, int maxMessages, String actorId) {
        if (!REPLAYABLE_TOPICS.contains(topic)) {
            throw new DomainValidationException(
                    "Unknown topic '" + topic + "'; replayable topics are " + REPLAYABLE_TOPICS);
        }
        if (maxMessages < 1 || maxMessages > MAX_MESSAGES_PER_CALL) {
            throw new DomainValidationException("maxMessages must be between 1 and " + MAX_MESSAGES_PER_CALL);
        }
        Objects.requireNonNull(actorId, "actorId must not be null");
        int replayed = replayer.replay(topic, maxMessages);
        auditLog.record(
                actorId,
                "DEAD_LETTERS_REPLAYED",
                "Topic",
                topic,
                Map.of("requested", maxMessages, "replayed", replayed));
        return replayed;
    }
}
