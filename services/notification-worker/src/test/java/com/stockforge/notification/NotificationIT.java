package com.stockforge.notification;

import static com.stockforge.notification.testsupport.KafkaTestClient.envelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.stockforge.events.OrderMessages;
import com.stockforge.events.Topics;
import com.stockforge.notification.testsupport.KafkaTestClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** FR-9 against a real broker and database: a redelivered event notifies the customer once. */
@SpringBootTest
@Import(NotificationIT.Containers.class)
class NotificationIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18.6");
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.3.1");
        }
    }

    @Autowired
    private KafkaConnectionDetails kafkaConnection;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aDuplicateOrderEventProducesOneNotification() {
        KafkaTestClient kafka = new KafkaTestClient(String.join(",", kafkaConnection.getBootstrapServers()));
        UUID order = UUID.randomUUID();
        var confirmed = envelope(
                OrderMessages.OrderConfirmed.TYPE,
                OrderMessages.AGGREGATE_ORDER,
                order.toString(),
                new OrderMessages.OrderConfirmed(order, UUID.randomUUID(), UUID.randomUUID(), "10.00", "USD"));
        var placed = envelope(
                OrderMessages.OrderCreated.TYPE,
                OrderMessages.AGGREGATE_ORDER,
                order.toString(),
                new OrderMessages.OrderCreated(order, UUID.randomUUID(), UUID.randomUUID(), "10.00", "USD"));

        kafka.send(Topics.ORDER_EVENTS, order.toString(), placed);
        kafka.send(Topics.ORDER_EVENTS, order.toString(), confirmed);
        kafka.send(Topics.ORDER_EVENTS, order.toString(), confirmed);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(jdbc.sql(
                                        "SELECT count(*) FROM notification_records WHERE order_id = :order AND status = 'SENT'")
                                .param("order", order)
                                .query(Integer.class)
                                .single())
                        .isOne());
        // Give the duplicate time to be (not) processed, then check again.
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(jdbc.sql("SELECT count(*) FROM notification_records WHERE order_id = :order")
                                        .param("order", order)
                                        .query(Integer.class)
                                        .single())
                                .isOne());
    }
}
