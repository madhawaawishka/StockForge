package com.stockforge.inventory.adapter.in.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Manages the gRPC server lifecycle within the Spring application context (spec §12, §33). */
@Component
class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final InventoryGrpcService grpcService;
    private final int configuredPort;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Server server;

    GrpcServerLifecycle(
            InventoryGrpcService grpcService,
            @Value("${inventory.grpc.port:9090}") int configuredPort,
            @Value("${inventory.grpc.enabled:true}") boolean enabled) {
        this.grpcService = grpcService;
        this.configuredPort = configuredPort;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled || running.get()) {
            return;
        }
        try {
            this.server = ServerBuilder.forPort(configuredPort)
                    .addService(grpcService)
                    .build()
                    .start();
            running.set(true);
            log.info("Inventory gRPC server started on port {}", server.getPort());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start gRPC server on port " + configuredPort, e);
        }
    }

    @Override
    public void stop() {
        if (!running.get() || server == null) {
            return;
        }
        log.info("Shutting down Inventory gRPC server...");
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
            log.info("Inventory gRPC server stopped.");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return server != null ? server.getPort() : configuredPort;
    }
}
