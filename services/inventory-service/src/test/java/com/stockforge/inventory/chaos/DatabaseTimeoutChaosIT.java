package com.stockforge.inventory.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@InventoryIntegrationTest
class DatabaseTimeoutChaosIT {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("Database query exceeding statement_timeout is aborted cleanly by PostgreSQL engine")
    void databaseStatementTimeoutAbortsSlowQuery() {
        // Assert that a query that exceeds the session statement_timeout throws DataAccessException with cancellation
        assertThatThrownBy(() -> {
                    jdbc.sql("""
                    DO $$
                    BEGIN
                        SET LOCAL statement_timeout = '150ms';
                        PERFORM pg_sleep(1);
                    END $$;
                    """).update();
                })
                .isInstanceOf(DataAccessException.class)
                .hasMessageMatching("(?i).*statement timeout.*|.*canceling statement.*");

        // Assert that the connection and database pool remain completely healthy for subsequent queries
        Integer result = jdbc.sql("SELECT 1").query(Integer.class).single();
        assertThat(result).isEqualTo(1);
    }
}
