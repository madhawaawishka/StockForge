package com.stockforge.inventory.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real dependencies of the service for integration tests, in the same versions as docker-compose.yml, so tests
 * exercise exactly the SQL dialect, broker and cache the service runs against (spec §29). {@link ServiceConnection}
 * points the service at the containers, so no connection properties are needed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InfrastructureContainers {

    public static final String POSTGRES_IMAGE = "postgres:18.6";
    public static final String KAFKA_IMAGE = "apache/kafka:4.3.1";
    public static final String REDIS_IMAGE = "redis:8.8.3";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(KAFKA_IMAGE);
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    }
}
