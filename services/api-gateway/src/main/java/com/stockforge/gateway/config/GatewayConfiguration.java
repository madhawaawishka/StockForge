package com.stockforge.gateway.config;

import com.stockforge.gateway.application.AdminUseCases;
import com.stockforge.gateway.application.StorefrontUseCases;
import com.stockforge.gateway.domain.port.InventoryApi;
import com.stockforge.gateway.domain.port.OrderApi;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root of the API gateway (ADR-004). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
class GatewayConfiguration {

    @Bean
    StorefrontUseCases storefrontUseCases(InventoryApi inventory, OrderApi orders, GatewayProperties properties) {
        return new StorefrontUseCases(inventory, orders, properties.paymentSimulationEnabled());
    }

    @Bean
    AdminUseCases adminUseCases(InventoryApi inventory, OrderApi orders) {
        return new AdminUseCases(inventory, orders);
    }

    @Bean
    OpenAPI stockforgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("StockForge API")
                        .version("v1")
                        .description("Flash-sale reservations and checkout. Errors are RFC 9457 Problem Details"
                                + " (problem types: docs/api/problems.md).\n\n"
                                + "**Try it locally:** run `python scripts/dev-token.py` (add `--admin` for the"
                                + " administration endpoints), press **Authorize** and paste the token. Every"
                                + " state-changing call needs a fresh `Idempotency-Key`; send the same one twice to see"
                                + " the replay (`Idempotent-Replayed: true`)."))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearer",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
