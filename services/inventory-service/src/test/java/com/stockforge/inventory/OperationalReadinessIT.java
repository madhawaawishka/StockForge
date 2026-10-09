package com.stockforge.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Verifies the operational contract: probes, metrics, build info, API docs and database session settings. */
@InventoryIntegrationTest
class OperationalReadinessIT {

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void livenessAndReadinessProbesAreUp() {
        client.get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
        client.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void prometheusExposesConnectionPoolMetrics() {
        client.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(metrics -> assertThat(metrics)
                        .contains("hikaricp_connections_max")
                        .contains("pool=\"inventory-db\"")
                        .contains("http_server_requests"));
    }

    @Test
    void infoEndpointReportsTheBuild() {
        client.get()
                .uri("/actuator/info")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.build.artifact")
                .isEqualTo("inventory-service");
    }

    @Test
    void openApiDocumentDescribesTheProductEndpoints() {
        client.get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.info.title")
                .isEqualTo("StockForge Inventory API")
                .jsonPath("$.paths['/api/v1/products']")
                .exists()
                .jsonPath("$.paths['/api/v1/admin/products']")
                .exists();
    }

    @Test
    void databaseSessionsUseTheServiceSchemaAndFailFastTimeouts() {
        assertThat(setting("SELECT current_schema()")).isEqualTo("inventory");
        assertThat(setting("SHOW statement_timeout")).isEqualTo("5s");
        assertThat(setting("SHOW lock_timeout")).isEqualTo("2s");
        assertThat(setting("SHOW idle_in_transaction_session_timeout")).isEqualTo("10s");
    }

    private String setting(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }
}
