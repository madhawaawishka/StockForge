package com.stockforge.notification.adapter.out.notification;

import com.stockforge.notification.domain.model.NotificationChannel;
import com.stockforge.notification.domain.port.NotificationSender;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Simulates external notification dispatch (e.g. SES, Twilio) with structured logging (spec §10). */
@Component
class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public boolean send(UUID userId, NotificationChannel channel, String message) {
        log.info("[SIMULATED NOTIFICATION] Sent {} to user {}: {}", channel, userId, message);
        return true;
    }
}
