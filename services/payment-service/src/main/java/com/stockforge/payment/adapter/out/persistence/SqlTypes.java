package com.stockforge.payment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class SqlTypes {

    private SqlTypes() {}

    static OffsetDateTime toTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
