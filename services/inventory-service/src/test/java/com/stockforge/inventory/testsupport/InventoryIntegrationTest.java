package com.stockforge.inventory.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The full service on a random port, backed by real PostgreSQL, Kafka and Redis containers.
 *
 * <p>Every integration test uses this one annotation so that all of them share a single cached Spring context and
 * therefore a single set of containers. Tests must not assume an empty database: they create their own data with unique
 * keys (see {@link TestData}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(InfrastructureContainers.class)
public @interface InventoryIntegrationTest {}
