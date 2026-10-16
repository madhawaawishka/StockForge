package com.stockforge.order.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The order service against real PostgreSQL and Kafka containers. gRPC runs in-process: the gateway's calls in tests
 * and the order service's own calls to Inventory both reach the same in-process server, where {@link FakeInventory}
 * plays the Inventory service. All integration tests share this one context and its containers.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import({InfrastructureContainers.class, FakeInventory.class})
public @interface OrderIntegrationTest {}
