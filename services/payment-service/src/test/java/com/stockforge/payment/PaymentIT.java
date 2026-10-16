package com.stockforge.payment;

import static com.stockforge.payment.testsupport.KafkaTestClient.envelope;
import static com.stockforge.payment.testsupport.KafkaTestClient.ofType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.stockforge.events.PaymentMessages;
import com.stockforge.events.Topics;
import com.stockforge.payment.testsupport.KafkaTestClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/** The payment service against a real broker and database: every simulated outcome, duplicates and refunds (FR-6). */
@SpringBootTest
@Import(PaymentIT.Containers.class)
class PaymentIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

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

    private KafkaTestClient kafka;

    @BeforeEach
    void setUp() {
        kafka = new KafkaTestClient(String.join(",", kafkaConnection.getBootstrapServers()));
    }

    @Test
    void eachRequestedOutcomeIsAnnouncedWithItsEvent() {
        String succeeded = requestPayment("SUCCEEDED");
        String failed = requestPayment("FAILED");
        String timedOut = requestPayment("TIMED_OUT");

        kafka.awaitRecord(Topics.PAYMENT_EVENTS, ofType(PaymentMessages.PaymentSucceeded.TYPE, succeeded), TIMEOUT);
        kafka.awaitRecord(Topics.PAYMENT_EVENTS, ofType(PaymentMessages.PaymentFailed.TYPE, failed), TIMEOUT);
        kafka.awaitRecord(Topics.PAYMENT_EVENTS, ofType(PaymentMessages.PaymentTimedOut.TYPE, timedOut), TIMEOUT);
        assertThat(status(succeeded)).isEqualTo("SUCCEEDED");
        assertThat(status(failed)).isEqualTo("FAILED");
        assertThat(status(timedOut)).isEqualTo("TIMED_OUT");
    }

    @Test
    void aLostProviderResponseLeavesThePaymentPendingAndAnnouncesNothing() {
        String order = requestPayment("NO_RESPONSE");

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(status(order)).isEqualTo("PENDING"));
        assertThat(kafka.count(Topics.PAYMENT_EVENTS, record -> order.equals(record.key()), Duration.ofSeconds(2)))
                .isZero();
    }

    @Test
    void aDuplicateRequestChargesOnce() {
        String order = UUID.randomUUID().toString();
        var request = envelope(
                PaymentMessages.RequestPayment.TYPE,
                PaymentMessages.AGGREGATE_PAYMENT,
                order,
                new PaymentMessages.RequestPayment(UUID.fromString(order), UUID.randomUUID(), "10.00", "USD", null));

        kafka.send(Topics.PAYMENT_COMMANDS, order, request);
        kafka.send(Topics.PAYMENT_COMMANDS, order, request);

        kafka.awaitRecord(Topics.PAYMENT_EVENTS, ofType(PaymentMessages.PaymentSucceeded.TYPE, order), TIMEOUT);
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :order")
                        .param("order", UUID.fromString(order))
                        .query(Integer.class)
                        .single())
                .isOne();
        assertThat(kafka.count(
                        Topics.PAYMENT_EVENTS,
                        ofType(PaymentMessages.PaymentSucceeded.TYPE, order),
                        Duration.ofSeconds(3)))
                .isOne();
    }

    @Test
    void aRefundCommandReturnsTheMoney() {
        String order = requestPayment("SUCCEEDED");
        kafka.awaitRecord(Topics.PAYMENT_EVENTS, ofType(PaymentMessages.PaymentSucceeded.TYPE, order), TIMEOUT);

        kafka.send(
                Topics.PAYMENT_COMMANDS,
                order,
                envelope(
                        PaymentMessages.RefundPayment.TYPE,
                        PaymentMessages.AGGREGATE_PAYMENT,
                        order,
                        new PaymentMessages.RefundPayment(UUID.fromString(order), "Reservation EXPIRED")));

        kafka.awaitRecord(Topics.PAYMENT_EVENTS, ofType(PaymentMessages.PaymentRefunded.TYPE, order), TIMEOUT);
        assertThat(status(order)).isEqualTo("REFUNDED");
    }

    private String requestPayment(String outcome) {
        String order = UUID.randomUUID().toString();
        kafka.send(
                Topics.PAYMENT_COMMANDS,
                order,
                envelope(
                        PaymentMessages.RequestPayment.TYPE,
                        PaymentMessages.AGGREGATE_PAYMENT,
                        order,
                        new PaymentMessages.RequestPayment(
                                UUID.fromString(order), UUID.randomUUID(), "25.00", "USD", outcome)));
        return order;
    }

    private String status(String order) {
        return jdbc.sql("SELECT status FROM payments WHERE order_id = :order")
                .param("order", UUID.fromString(order))
                .query(String.class)
                .optional()
                .orElse("NONE");
    }
}
