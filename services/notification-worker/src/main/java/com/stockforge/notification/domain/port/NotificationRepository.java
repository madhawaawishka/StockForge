package com.stockforge.notification.domain.port;

import com.stockforge.notification.domain.model.Notification;
import com.stockforge.notification.domain.model.NotificationId;
import java.time.Instant;

/** Notifications, at most one per triggering event ({@code UNIQUE (event_id)}): the worker's inbox (spec §18). */
public interface NotificationRepository {

    /** Stores the notification unless one exists for its event; returns the stored notification either way. */
    Notification registerOnce(Notification notification);

    void markSent(NotificationId id, Instant sentAt);
}
