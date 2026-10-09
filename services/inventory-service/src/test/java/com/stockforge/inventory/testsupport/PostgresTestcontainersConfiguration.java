package com.stockforge.inventory.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL for integration tests. {@link ServiceConnection} points the datasource at the container, so no
 * connection properties are needed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    /** Same version as docker-compose.yml, so tests exercise exactly the SQL dialect the service runs against. */
    public static final String POSTGRES_IMAGE = "postgres:18.6";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
}
