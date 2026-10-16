package com.stockforge.notification.adapter.out.notification;

import com.stockforge.notification.domain.model.Notification;
import com.stockforge.notification.domain.port.NotificationSender;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simulated delivery (spec §5: no real email or SMS). Logs ids only, never the message body or contact details (spec
 * §25 logging hygiene), and counts deliveries in {@code stockforge_notifications_sent_total{type}}.
 */
@Component
class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    private final MeterRegistry meters;

    LoggingNotificationSender(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void send(Notification notification) {
        log.info(
                "Sent {} notification {} about order {} via {}",
                notification.eventType(),
                notification.id().value(),
                notification.orderId(),
                notification.channel());
        meters.counter("stockforge.notifications.sent", "type", notification.eventType())
                .increment();
    }
}
