package com.peoplehub.support;

import java.sql.SQLException;

/** Reads the PostgreSQL error out of whatever exception a JDBC or Spring call threw. */
public final class SqlErrors {

    public static final String CHECK_VIOLATION = "23514";
    public static final String NOT_NULL_VIOLATION = "23502";
    public static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** A {@code RAISE EXCEPTION} from PL/pgSQL, which is how the append-only trigger rejects. */
    public static final String RAISED_EXCEPTION = "P0001";

    private SqlErrors() {}

    /** SQLSTATE of the first {@link SQLException} in the cause chain, or {@code null}. */
    public static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e && e.getSQLState() != null) {
                return e.getSQLState();
            }
        }
        return null;
    }

    /** Message of the first {@link SQLException} in the cause chain, or {@code null}. */
    public static String sqlMessage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e) {
                return e.getMessage();
            }
        }
        return null;
    }
}
