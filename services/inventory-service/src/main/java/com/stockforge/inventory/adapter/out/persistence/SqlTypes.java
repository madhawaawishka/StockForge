package com.stockforge.inventory.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Conversions between domain types and JDBC types. The PostgreSQL driver maps {@code TIMESTAMPTZ} to
 * {@link OffsetDateTime} but cannot bind {@link Instant} directly.
 */
final class SqlTypes {

    private SqlTypes() {}

    static OffsetDateTime toTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
