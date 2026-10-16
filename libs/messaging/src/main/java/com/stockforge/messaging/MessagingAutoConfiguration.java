package com.stockforge.messaging;

import com.stockforge.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the messaging infrastructure into a service. Each service still owns its tables ({@code outbox_events},
 * {@code processed_events}) through its own Flyway migrations and its own database role (ADR-002).
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
            "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
            "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration"
        })
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    EnvelopeCodec envelopeCodec(ObjectProvider<JsonMapper> jsonMapper) {
        return new EnvelopeCodec(
                jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()));
    }

    @Bean
    @ConditionalOnMissingBean(name = "messagingTraceContext")
    TraceContextSupport messagingTraceContext(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        Tracer activeTracer = tracer.getIfAvailable();
        Propagator activePropagator = propagator.getIfAvailable();
        if (activeTracer == null || activePropagator == null || Tracer.NOOP.equals(activeTracer)) {
            return TraceContextSupport.disabled();
        }
        return new TraceContextSupport(activeTracer, activePropagator);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(
            JdbcClient jdbc,
            EnvelopeCodec codec,
            @Qualifier("messagingTraceContext") TraceContextSupport tracing,
            ObjectProvider<Clock> clock) {
        return new OutboxWriter(jdbc, codec, tracing, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessedMessageStore processedMessageStore(JdbcClient jdbc) {
        return new ProcessedMessageStore(jdbc);
    }

    @Bean
    @ConditionalOnProperty(name = "stockforge.messaging.outbox.relay-enabled", matchIfMissing = true)
    OutboxRelay outboxRelay(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            KafkaTemplate<String, String> kafka,
            EnvelopeCodec codec,
            @Qualifier("messagingTraceContext") TraceContextSupport tracing,
            MessagingProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new OutboxRelay(
                jdbc,
                new TransactionTemplate(transactionManager),
                kafka,
                codec,
                tracing,
                properties.outbox(),
                new OutboxMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new)));
    }

    @Bean
    MessagingScheduler messagingScheduler(
            ObjectProvider<OutboxRelay> relay, JdbcClient jdbc, MessagingProperties properties) {
        OutboxRelay activeRelay = relay.getIfAvailable();
        return new MessagingScheduler(
                activeRelay, new MessagingRetention(jdbc, properties, activeRelay != null), properties.outbox());
    }

    /** Picked up by Spring Boot's listener container factory for every {@code @KafkaListener}. */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    CommonErrorHandler deadLetterErrorHandler(
            KafkaTemplate<String, String> kafka,
            MessagingProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry) {
        return DeadLetterErrorHandling.errorHandler(
                kafka,
                properties.consumer(),
                properties.topics().deadLetters(),
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * Topics are created explicitly with a chosen partition count, never implicitly by a producer (the broker has
     * auto-creation disabled). Creation is idempotent; a missing broker at startup is logged, not fatal.
     */
    @Bean
    KafkaAdmin.NewTopics stockforgeTopics(MessagingProperties properties) {
        MessagingProperties.Topics config = properties.topics();
        List<NewTopic> topics = new ArrayList<>();
        config.declare().forEach(name -> topics.add(topic(name, config)));
        config.deadLetters().forEach(name -> topics.add(topic(Topics.deadLetterTopicOf(name), config)));
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    private static NewTopic topic(String name, MessagingProperties.Topics config) {
        return TopicBuilder.name(name)
                .partitions(config.partitions())
                .replicas(config.replicas())
                .build();
    }
}
