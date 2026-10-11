package com.stockforge.notification.domain.port;

import com.stockforge.notification.domain.model.NotificationRecord;

public interface NotificationRecordRepository {

    void insert(NotificationRecord record);
}
