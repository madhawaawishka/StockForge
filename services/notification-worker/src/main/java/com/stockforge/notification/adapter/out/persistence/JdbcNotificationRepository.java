package com.stockforge.notification.adapter.out.persistence;

import com.stockforge.notification.domain.model.Notification;
import com.stockforge.notification.domain.model.NotificationChannel;
import com.stockforge.notification.domain.model.NotificationId;
import com.stockforge.notification.domain.model.NotificationStatus;
import com.stockforge.notification.domain.port.NotificationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNotificationRepository implements NotificationRepository {

    private final JdbcClient jdbc;

    JdbcNotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Notification registerOnce(Notification notification) {
        jdbc.sql("""
                        INSERT INTO notification_records (id, event_id, order_id, user_id, event_type, channel, status,
                                                          message, created_at)
                        VALUES (:id, :eventId, :orderId, :userId, :eventType, :channel, :status, :message, :createdAt)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("id", notification.id().value())
                .param("eventId", notification.eventId())
                .param("orderId", notification.orderId())
                .param("userId", notification.userId())
                .param("eventType", notification.eventType())
                .param("channel", notification.channel().name())
                .param("status", notification.status().name())
                .param("message", notification.message())
                .param("createdAt", notification.createdAt().atOffset(ZoneOffset.UTC))
                .update();
        return jdbc.sql("""
                        SELECT id, event_id, order_id, user_id, event_type, channel, status, message, created_at,
                               sent_at
                        FROM notification_records WHERE event_id = :eventId
                        """)
                .param("eventId", notification.eventId())
                .query(JdbcNotificationRepository::mapRow)
                .single();
    }

    @Override
    public void markSent(NotificationId id, Instant sentAt) {
        jdbc.sql(
                        "UPDATE notification_records SET status = 'SENT', sent_at = :sentAt WHERE id = :id AND status = 'PENDING'")
                .param("id", id.value())
                .param("sentAt", sentAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static Notification mapRow(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime sentAt = rs.getObject("sent_at", OffsetDateTime.class);
        return new Notification(
                NotificationId.of(rs.getObject("id", UUID.class)),
                rs.getObject("event_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("event_type"),
                NotificationChannel.valueOf(rs.getString("channel")),
                NotificationStatus.valueOf(rs.getString("status")),
                rs.getString("message"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(sentAt).map(OffsetDateTime::toInstant));
    }
}
