package com.stockforge.notification.domain.port;

import com.stockforge.notification.domain.model.Notification;

/**
 * Delivers a notification (email/SMS are simulated, spec §5). A remote call, so never made inside a transaction. Throws
 * if delivery failed; the message is then retried and, after the last attempt, dead-lettered (spec §22).
 */
public interface NotificationSender {

    void send(Notification notification);
}
