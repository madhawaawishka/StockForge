package com.stockforge.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stockforge.grpc")
public record GrpcClientProperties(String inventoryHost, int inventoryPort, String orderHost, int orderPort) {

    public GrpcClientProperties {
        if (inventoryHost == null || inventoryHost.isBlank()) {
            inventoryHost = "localhost";
        }
        if (inventoryPort <= 0) {
            inventoryPort = 9091;
        }
        if (orderHost == null || orderHost.isBlank()) {
            orderHost = "localhost";
        }
        if (orderPort <= 0) {
            orderPort = 9092;
        }
    }
}
