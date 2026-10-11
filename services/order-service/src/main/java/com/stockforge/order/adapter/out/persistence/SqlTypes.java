package com.stockforge.order.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

final class SqlTypes {

    private SqlTypes() {}

    static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS)) : null;
    }

    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant().truncatedTo(ChronoUnit.MICROS) : null;
    }
}
