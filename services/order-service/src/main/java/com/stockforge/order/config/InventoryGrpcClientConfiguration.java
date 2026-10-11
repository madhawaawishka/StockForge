package com.stockforge.order.config;

import com.stockforge.inventory.v1.InventoryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InventoryGrpcClientConfiguration {

    @Bean(destroyMethod = "shutdown")
    ManagedChannel inventoryManagedChannel(
            @Value("${inventory.grpc.host:localhost}") String host, @Value("${inventory.grpc.port:9090}") int port) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }

    @Bean
    InventoryServiceGrpc.InventoryServiceBlockingStub inventoryBlockingStub(ManagedChannel inventoryManagedChannel) {
        return InventoryServiceGrpc.newBlockingStub(inventoryManagedChannel);
    }
}
