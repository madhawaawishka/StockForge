package com.stockforge.notification.application;

import com.stockforge.notification.domain.model.NotificationChannel;
import java.util.UUID;

public record SendNotificationCommand(
        UUID orderId, UUID userId, String eventType, NotificationChannel channel, String message) {}
