package com.stockforge.notification.domain.model;

import java.util.Objects;
import java.util.UUID;

public record NotificationId(UUID value) {

    public NotificationId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static NotificationId of(UUID value) {
        return new NotificationId(value);
    }
}
