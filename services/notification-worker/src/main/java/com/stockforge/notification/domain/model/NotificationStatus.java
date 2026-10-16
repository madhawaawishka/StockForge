package com.stockforge.notification.domain.model;

/** Delivery state of a notification. PENDING until the sender confirmed delivery. */
public enum NotificationStatus {
    PENDING,
    SENT
}
