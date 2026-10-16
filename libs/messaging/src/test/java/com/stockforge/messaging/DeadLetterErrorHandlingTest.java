package com.stockforge.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class DeadLetterErrorHandlingTest {

    /** Nothing is sent in these tests; the template is never connected. */
    private final KafkaTemplate<String, String> kafka = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));

    @Test
    void theDeadLetterCounterOfEveryConsumedTopicExistsBeforeTheFirstDeadLetter() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        DeadLetterErrorHandling.errorHandler(
                kafka,
                new MessagingProperties.Consumer(4, Duration.ofMillis(500), Duration.ofSeconds(5)),
                List.of("payment-events", "inventory-events"),
                meters);

        // An alert on increase() can only see the first dead letter if the series already exists at zero.
        assertThat(meters.find("stockforge.kafka.dead.lettered").counters())
                .extracting(counter -> counter.getId().getTag("topic") + "=" + counter.count())
                .containsExactlyInAnyOrder("payment-events=0.0", "inventory-events=0.0");
    }
}
