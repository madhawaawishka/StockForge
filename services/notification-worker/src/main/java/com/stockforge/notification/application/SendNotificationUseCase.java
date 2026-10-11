package com.stockforge.notification.application;

import com.stockforge.notification.domain.model.NotificationId;
import com.stockforge.notification.domain.model.NotificationRecord;
import com.stockforge.notification.domain.model.NotificationStatus;
import com.stockforge.notification.domain.port.NotificationRecordRepository;
import com.stockforge.notification.domain.port.NotificationSender;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Handles sending notifications and persisting delivery records (spec §10). */
public class SendNotificationUseCase {

    private final NotificationSender sender;
    private final NotificationRecordRepository repository;
    private final Clock clock;

    public SendNotificationUseCase(NotificationSender sender, NotificationRecordRepository repository, Clock clock) {
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public NotificationRecord execute(SendNotificationCommand command) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        boolean sent = sender.send(command.userId(), command.channel(), command.message());
        NotificationStatus status = sent ? NotificationStatus.SENT : NotificationStatus.FAILED;

        NotificationRecord record = new NotificationRecord(
                NotificationId.of(UUID.randomUUID()),
                command.orderId(),
                command.userId(),
                command.eventType(),
                command.channel(),
                status,
                command.message(),
                now);

        repository.insert(record);
        return record;
    }
}
