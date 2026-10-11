package com.stockforge.notification.domain.port;

import com.stockforge.notification.domain.model.NotificationChannel;
import java.util.UUID;

public interface NotificationSender {

    boolean send(UUID userId, NotificationChannel channel, String message);
}
