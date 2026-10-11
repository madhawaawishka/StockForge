package com.stockforge.gateway.config;

import com.stockforge.gateway.application.OrderGatewayUseCase;
import com.stockforge.gateway.application.ProductGatewayQueries;
import com.stockforge.gateway.application.ReservationGatewayUseCase;
import com.stockforge.gateway.domain.port.InventoryClient;
import com.stockforge.gateway.domain.port.OrderClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfiguration {

    @Bean
    public ProductGatewayQueries productGatewayQueries(InventoryClient inventoryClient) {
        return new ProductGatewayQueries(inventoryClient);
    }

    @Bean
    public ReservationGatewayUseCase reservationGatewayUseCase(InventoryClient inventoryClient) {
        return new ReservationGatewayUseCase(inventoryClient);
    }

    @Bean
    public OrderGatewayUseCase orderGatewayUseCase(OrderClient orderClient) {
        return new OrderGatewayUseCase(orderClient);
    }
}
