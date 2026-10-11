package com.stockforge.order.config;

import com.stockforge.order.adapter.out.id.UuidV7Generator;
import com.stockforge.order.application.CancelOrderUseCase;
import com.stockforge.order.application.CreateOrderUseCase;
import com.stockforge.order.application.GetOrderUseCase;
import com.stockforge.order.domain.port.IdGenerator;
import com.stockforge.order.domain.port.IdempotencyKeyRepository;
import com.stockforge.order.domain.port.OrderRepository;
import com.stockforge.order.domain.port.OutboxPublisher;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
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
    CreateOrderUseCase createOrderUseCase(
            OrderRepository orders,
            IdempotencyKeyRepository idempotencyKeys,
            OutboxPublisher outbox,
            IdGenerator idGenerator,
            Clock clock) {
        return new CreateOrderUseCase(orders, idempotencyKeys, outbox, idGenerator, clock);
    }

    @Bean
    GetOrderUseCase getOrderUseCase(OrderRepository orders) {
        return new GetOrderUseCase(orders);
    }

    @Bean
    CancelOrderUseCase cancelOrderUseCase(OrderRepository orders, OutboxPublisher outbox, Clock clock) {
        return new CancelOrderUseCase(orders, outbox, clock);
    }

    @Bean
    com.stockforge.order.application.ProcessPaymentOutcomeUseCase processPaymentOutcomeUseCase(
            OrderRepository orders,
            com.stockforge.order.domain.port.InventoryClient inventoryClient,
            OutboxPublisher outbox,
            Clock clock) {
        return new com.stockforge.order.application.ProcessPaymentOutcomeUseCase(
                orders, inventoryClient, outbox, clock);
    }
}
