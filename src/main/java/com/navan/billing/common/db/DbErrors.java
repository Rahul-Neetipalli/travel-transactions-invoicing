package com.navan.billing.common.db;

import java.sql.SQLException;
import java.util.Set;

/** Classifies JDBC failures by SQLState / vendor code, independent of Spring's exception translation. */
public final class DbErrors {

    private static final Set<String> RETRYABLE_STATES = Set.of("40001", "40P01", "55P03", "HYT00", "90131");
    private static final Set<Integer> RETRYABLE_CODES = Set.of(40001, 50200, 90131);

    private DbErrors() {
    }

    public static boolean isUniqueViolation(Throwable failure) {
        return anySql(failure, e -> "23505".equals(e.getSQLState()) || e.getErrorCode() == 23505);
    }

    /** Lock wait timeout, deadlock victim, serialization failure or H2 concurrent-update. */
    public static boolean isRetryable(Throwable failure) {
        return anySql(failure, e -> RETRYABLE_STATES.contains(e.getSQLState()) || RETRYABLE_CODES.contains(e.getErrorCode()));
    }

    private static boolean anySql(Throwable failure, java.util.function.Predicate<SQLException> test) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException e && test.test(e)) {
                return true;
            }
        }
        return false;
    }
}
