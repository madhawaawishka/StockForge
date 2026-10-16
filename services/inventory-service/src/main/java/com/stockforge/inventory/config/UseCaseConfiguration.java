package com.stockforge.inventory.config;

import com.stockforge.inventory.adapter.in.TransientFailureRetry;
import com.stockforge.inventory.adapter.out.id.UuidV7Generator;
import com.stockforge.inventory.application.AdjustStockUseCase;
import com.stockforge.inventory.application.CancelReservationUseCase;
import com.stockforge.inventory.application.CreateProductUseCase;
import com.stockforge.inventory.application.ExpireReservationsUseCase;
import com.stockforge.inventory.application.GetReservationUseCase;
import com.stockforge.inventory.application.ProductCatalogQueries;
import com.stockforge.inventory.application.PurgeExpiredIdempotencyKeysUseCase;
import com.stockforge.inventory.application.ReconcileInventoryUseCase;
import com.stockforge.inventory.application.ReservationCommandHandler;
import com.stockforge.inventory.application.ReservationPolicy;
import com.stockforge.inventory.application.ReserveStockUseCase;
import com.stockforge.inventory.domain.port.AuditLog;
import com.stockforge.inventory.domain.port.IdGenerator;
import com.stockforge.inventory.domain.port.IdempotencyKeyRepository;
import com.stockforge.inventory.domain.port.InventoryReconciliation;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.OutboxPublisher;
import com.stockforge.inventory.domain.port.ProcessedMessages;
import com.stockforge.inventory.domain.port.ProductCache;
import com.stockforge.inventory.domain.port.ProductRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Composition root: wires use cases explicitly instead of annotating them with Spring stereotypes, which keeps the
 * application layer free of framework dependencies apart from transaction demarcation (ADR-004).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(InventoryProperties.class)
class UseCaseConfiguration {

    /** Whole-transaction retries on serialization failures and deadlocks (spec §14 F). */
    private static final int TRANSACTION_ATTEMPTS = 3;

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator(Clock clock) {
        return new UuidV7Generator(clock);
    }

    @Bean
    ReservationPolicy reservationPolicy(InventoryProperties properties) {
        return new ReservationPolicy(
                properties.reservation().ttl(),
                properties.reservation().purchaseLimit(),
                properties.idempotency().keyTtl());
    }

    @Bean
    TransientFailureRetry transientFailureRetry() {
        return new TransientFailureRetry(TRANSACTION_ATTEMPTS);
    }

    @Bean
    CreateProductUseCase createProductUseCase(
            ProductRepository products,
            InventoryRepository inventories,
            AuditLog auditLog,
            IdGenerator idGenerator,
            Clock clock) {
        return new CreateProductUseCase(products, inventories, auditLog, idGenerator, clock);
    }

    @Bean
    ProductCatalogQueries productCatalogQueries(
            ProductRepository products, InventoryRepository inventories, ProductCache productCache) {
        return new ProductCatalogQueries(products, inventories, productCache);
    }

    @Bean
    AdjustStockUseCase adjustStockUseCase(
            InventoryRepository inventories,
            IdempotencyKeyRepository idempotencyKeys,
            AuditLog auditLog,
            ProductCache productCache,
            Clock clock,
            ReservationPolicy policy) {
        return new AdjustStockUseCase(inventories, idempotencyKeys, auditLog, productCache, clock, policy);
    }

    @Bean
    ReserveStockUseCase reserveStockUseCase(
            ProductRepository products,
            StockReservationStrategy strategy,
            ReservationRepository reservations,
            IdempotencyKeyRepository idempotencyKeys,
            UserPurchaseLimitRepository purchaseLimits,
            OutboxPublisher outbox,
            IdGenerator idGenerator,
            Clock clock,
            ReservationPolicy policy) {
        return new ReserveStockUseCase(
                products, strategy, reservations, idempotencyKeys, purchaseLimits, outbox, idGenerator, clock, policy);
    }

    @Bean
    GetReservationUseCase getReservationUseCase(ReservationRepository reservations) {
        return new GetReservationUseCase(reservations);
    }

    @Bean
    CancelReservationUseCase cancelReservationUseCase(
            ReservationRepository reservations,
            InventoryRepository inventories,
            UserPurchaseLimitRepository purchaseLimits,
            OutboxPublisher outbox,
            Clock clock) {
        return new CancelReservationUseCase(reservations, inventories, purchaseLimits, outbox, clock);
    }

    @Bean
    ReservationCommandHandler reservationCommandHandler(
            ProcessedMessages inbox,
            ReservationRepository reservations,
            InventoryRepository inventories,
            UserPurchaseLimitRepository purchaseLimits,
            OutboxPublisher outbox,
            Clock clock) {
        return new ReservationCommandHandler(inbox, reservations, inventories, purchaseLimits, outbox, clock);
    }

    @Bean
    ExpireReservationsUseCase expireReservationsUseCase(
            ReservationRepository reservations,
            InventoryRepository inventories,
            UserPurchaseLimitRepository purchaseLimits,
            OutboxPublisher outbox,
            Clock clock) {
        return new ExpireReservationsUseCase(reservations, inventories, purchaseLimits, outbox, clock);
    }

    @Bean
    ReconcileInventoryUseCase reconcileInventoryUseCase(InventoryReconciliation reconciliation) {
        return new ReconcileInventoryUseCase(reconciliation);
    }

    @Bean
    PurgeExpiredIdempotencyKeysUseCase purgeExpiredIdempotencyKeysUseCase(
            IdempotencyKeyRepository idempotencyKeys, Clock clock) {
        return new PurgeExpiredIdempotencyKeysUseCase(idempotencyKeys, clock);
    }
}
