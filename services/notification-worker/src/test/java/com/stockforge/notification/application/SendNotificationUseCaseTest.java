package com.stockforge.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.notification.domain.model.NotificationChannel;
import com.stockforge.notification.domain.model.NotificationRecord;
import com.stockforge.notification.domain.model.NotificationStatus;
import com.stockforge.notification.domain.port.NotificationRecordRepository;
import com.stockforge.notification.domain.port.NotificationSender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SendNotificationUseCaseTest {

    private final List<NotificationRecord> savedRecords = new ArrayList<>();
    private final NotificationRecordRepository repository = savedRecords::add;
    private final NotificationSender successfulSender = (userId, channel, message) -> true;
    private final NotificationSender failingSender = (userId, channel, message) -> false;

    private Clock clock;

    @BeforeEach
    void setUp() {
        savedRecords.clear();
        clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void executeSuccessfullyDispatchesAndPersistsRecord() {
        SendNotificationUseCase useCase = new SendNotificationUseCase(successfulSender, repository, clock);
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        SendNotificationCommand command = new SendNotificationCommand(
                orderId, userId, "OrderConfirmed", NotificationChannel.EMAIL, "Order confirmed!");

        NotificationRecord record = useCase.execute(command);

        assertThat(record.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(savedRecords).hasSize(1);
        assertThat(savedRecords.get(0).orderId()).isEqualTo(orderId);
    }

    @Test
    void executeFailedDispatchPersistsFailedRecord() {
        SendNotificationUseCase useCase = new SendNotificationUseCase(failingSender, repository, clock);
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        SendNotificationCommand command = new SendNotificationCommand(
                orderId, userId, "OrderCancelled", NotificationChannel.SMS, "Order cancelled!");

        NotificationRecord record = useCase.execute(command);

        assertThat(record.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(savedRecords).hasSize(1);
        assertThat(savedRecords.get(0).status()).isEqualTo(NotificationStatus.FAILED);
    }
}
