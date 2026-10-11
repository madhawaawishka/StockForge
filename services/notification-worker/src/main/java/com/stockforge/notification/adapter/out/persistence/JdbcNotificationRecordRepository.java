package com.stockforge.notification.adapter.out.persistence;

import com.stockforge.notification.domain.model.NotificationRecord;
import com.stockforge.notification.domain.port.NotificationRecordRepository;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNotificationRecordRepository implements NotificationRecordRepository {

    private final JdbcClient jdbc;

    JdbcNotificationRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(NotificationRecord record) {
        jdbc.sql("""
                        INSERT INTO notification_records (id, order_id, user_id, event_type, channel, status, message, created_at)
                        VALUES (:id, :orderId, :userId, :eventType, :channel, :status, :message, :createdAt)
                        """)
                .param("id", record.id().value())
                .param("orderId", record.orderId())
                .param("userId", record.userId())
                .param("eventType", record.eventType())
                .param("channel", record.channel().name())
                .param("status", record.status().name())
                .param("message", record.message())
                .param("createdAt", record.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
