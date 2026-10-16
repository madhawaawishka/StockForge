package com.stockforge.order.config;

import com.stockforge.order.adapter.out.id.UuidV7Generator;
import com.stockforge.order.application.CheckoutPolicy;
import com.stockforge.order.application.CheckoutSaga;
import com.stockforge.order.application.OrderQueries;
import com.stockforge.order.application.PlaceOrderTransaction;
import com.stockforge.order.application.PlaceOrderUseCase;
import com.stockforge.order.application.PurgeExpiredIdempotencyKeysUseCase;
import com.stockforge.order.application.ReplayDeadLettersUseCase;
import com.stockforge.order.domain.port.AuditLog;
import com.stockforge.order.domain.port.DeadLetterReplayer;
import com.stockforge.order.domain.port.IdGenerator;
import com.stockforge.order.domain.port.IdempotencyKeyRepository;
import com.stockforge.order.domain.port.InventoryCatalog;
import com.stockforge.order.domain.port.OrderRepository;
import com.stockforge.order.domain.port.OutboxPublisher;
import com.stockforge.order.domain.port.ProcessedMessages;
import com.stockforge.order.domain.port.SagaObserver;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Composition root of the order service: use cases are wired explicitly (ADR-004). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OrderProperties.class)
class UseCaseConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator(Clock clock) {
        return new UuidV7Generator(clock);
    }

    @Bean
    CheckoutPolicy checkoutPolicy(OrderProperties properties) {
        return new CheckoutPolicy(
                properties.checkout().paymentTimeout(), properties.idempotency().keyTtl());
    }

    @Bean
    PlaceOrderTransaction placeOrderTransaction(
            OrderRepository orders,
            IdempotencyKeyRepository idempotencyKeys,
            OutboxPublisher outbox,
            CheckoutPolicy policy) {
        return new PlaceOrderTransaction(orders, idempotencyKeys, outbox, policy);
    }

    @Bean
    PlaceOrderUseCase placeOrderUseCase(
            InventoryCatalog inventory, PlaceOrderTransaction transaction, IdGenerator idGenerator, Clock clock) {
        return new PlaceOrderUseCase(inventory, transaction, idGenerator, clock);
    }

    @Bean
    CheckoutSaga checkoutSaga(
            ProcessedMessages inbox,
            OrderRepository orders,
            OutboxPublisher outbox,
            SagaObserver observer,
            Clock clock,
            CheckoutPolicy policy) {
        return new CheckoutSaga(inbox, orders, outbox, observer, clock, policy);
    }

    @Bean
    OrderQueries orderQueries(OrderRepository orders) {
        return new OrderQueries(orders);
    }

    @Bean
    ReplayDeadLettersUseCase replayDeadLettersUseCase(DeadLetterReplayer replayer, AuditLog auditLog) {
        return new ReplayDeadLettersUseCase(replayer, auditLog);
    }

    @Bean
    PurgeExpiredIdempotencyKeysUseCase purgeExpiredIdempotencyKeysUseCase(
            IdempotencyKeyRepository idempotencyKeys, Clock clock) {
        return new PurgeExpiredIdempotencyKeysUseCase(idempotencyKeys, clock);
    }
}
