package com.stockforge.notification.application;

import com.stockforge.notification.domain.model.Notification;
import com.stockforge.notification.domain.port.NotificationRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** The short transactions around sending, in their own bean so that sending happens between them (spec §15). */
public class NotificationTransactions {

    private final NotificationRepository notifications;

    public NotificationTransactions(NotificationRepository notifications) {
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
    }

    @Transactional
    public Notification register(Notification notification) {
        return notifications.registerOnce(notification);
    }

    @Transactional
    public void markSent(Notification notification, Instant sentAt) {
        notifications.markSent(notification.id(), sentAt);
    }
}
