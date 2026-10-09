package com.stockforge.inventory.config;

import com.stockforge.inventory.adapter.out.id.UuidV7Generator;
import com.stockforge.inventory.application.CancelReservationUseCase;
import com.stockforge.inventory.application.CreateProductUseCase;
import com.stockforge.inventory.application.GetReservationUseCase;
import com.stockforge.inventory.application.ProductCatalogQueries;
import com.stockforge.inventory.application.ReserveStockUseCase;
import com.stockforge.inventory.domain.port.IdGenerator;
import com.stockforge.inventory.domain.port.IdempotencyKeyRepository;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ProductRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires use cases explicitly instead of annotating them with Spring stereotypes, which keeps the application layer free
 * of framework dependencies apart from transaction demarcation (ADR-004).
 */
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
    CreateProductUseCase createProductUseCase(
            ProductRepository products, InventoryRepository inventories, IdGenerator idGenerator, Clock clock) {
        return new CreateProductUseCase(products, inventories, idGenerator, clock);
    }

    @Bean
    ProductCatalogQueries productCatalogQueries(ProductRepository products, InventoryRepository inventories) {
        return new ProductCatalogQueries(products, inventories);
    }

    @Bean
    ReserveStockUseCase reserveStockUseCase(
            ProductRepository products,
            StockReservationStrategy strategy,
            ReservationRepository reservations,
            IdempotencyKeyRepository idempotencyKeys,
            UserPurchaseLimitRepository purchaseLimits,
            IdGenerator idGenerator,
            Clock clock) {
        return new ReserveStockUseCase(
                products, strategy, reservations, idempotencyKeys, purchaseLimits, idGenerator, clock);
    }

    @Bean
    CancelReservationUseCase cancelReservationUseCase(
            ReservationRepository reservations,
            InventoryRepository inventories,
            UserPurchaseLimitRepository purchaseLimits,
            Clock clock) {
        return new CancelReservationUseCase(reservations, inventories, purchaseLimits, clock);
    }

    @Bean
    GetReservationUseCase getReservationUseCase(ReservationRepository reservations) {
        return new GetReservationUseCase(reservations);
    }
}
