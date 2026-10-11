package com.stockforge.gateway.config;

import com.stockforge.inventory.v1.InventoryServiceGrpc;
import com.stockforge.order.v1.OrderServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GrpcClientProperties.class)
public class GrpcClientConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel inventoryChannel(GrpcClientProperties props) {
        return ManagedChannelBuilder.forAddress(props.inventoryHost(), props.inventoryPort())
                .usePlaintext()
                .build();
    }

    @Bean
    public InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub(ManagedChannel inventoryChannel) {
        return InventoryServiceGrpc.newBlockingStub(inventoryChannel);
    }

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel orderChannel(GrpcClientProperties props) {
        return ManagedChannelBuilder.forAddress(props.orderHost(), props.orderPort())
                .usePlaintext()
                .build();
    }

    @Bean
    public OrderServiceGrpc.OrderServiceBlockingStub orderStub(ManagedChannel orderChannel) {
        return OrderServiceGrpc.newBlockingStub(orderChannel);
    }
}
