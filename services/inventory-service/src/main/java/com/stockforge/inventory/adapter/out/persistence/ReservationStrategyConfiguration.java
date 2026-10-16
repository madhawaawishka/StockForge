package com.stockforge.inventory.adapter.out.persistence;

import com.stockforge.inventory.config.InventoryProperties;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the one concurrency-control strategy selected by {@code inventory.reservation.strategy} (spec §14, ADR-006).
 * All strategies honour the same contract and pass the same concurrency test suite, so switching is a configuration
 * change, which is how the strategy benchmarks run.
 */
@Configuration(proxyBeanMethods = false)
class ReservationStrategyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ReservationStrategyConfiguration.class);

    @Bean
    StockReservationStrategy stockReservationStrategy(
            InventoryProperties properties, InventoryRepository inventories, ReservationRepository reservations) {
        InventoryProperties.Reservation config = properties.reservation();
        StockReservationStrategy strategy = switch (config.strategy()) {
            case ATOMIC_CONDITIONAL_UPDATE -> new AtomicConditionalUpdateReservationStrategy(inventories, reservations);
            case PESSIMISTIC_LOCKING -> new PessimisticLockingReservationStrategy(inventories, reservations);
            case OPTIMISTIC_LOCKING ->
                new OptimisticLockingReservationStrategy(inventories, reservations, config.optimisticMaxAttempts());
            case NAIVE -> {
                log.warn("Reservation strategy NAIVE is active: this instance WILL oversell under load");
                yield new NaiveReservationStrategy(inventories, reservations);
            }
        };
        log.info("Reservation concurrency strategy: {}", strategy.type());
        return strategy;
    }
}
