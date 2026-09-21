package com.peoplehub.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The database roles the test containers are provisioned with ({@code
 * testcontainers/db-roles.sql}), and connections as each of them (B0-6/3).
 *
 * <p>Existing tests keep using the container's own superuser for both Flyway and the application;
 * only the tests that prove the privilege boundary connect as these roles.
 */
public final class TestDatabaseRoles {

    public static final String INIT_SCRIPT = "testcontainers/db-roles.sql";

    /** Least-privileged application role: the default {@code runtime_role} placeholder value. */
    public static final String RUNTIME_ROLE = "peoplehub_app";

    public static final String RUNTIME_PASSWORD = "peoplehub_app";

    /** Non-superuser migration/owner role. */
    public static final String OWNER_ROLE = "peoplehub_owner";

    public static final String OWNER_PASSWORD = "peoplehub_owner";

    private TestDatabaseRoles() {}

    public static Connection runtimeConnection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
    }

    public static Connection ownerConnection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), OWNER_ROLE, OWNER_PASSWORD);
    }

    /** A DataSource that opens a new connection per use, as the least-privileged role. */
    public static DataSource runtimeDataSource(PostgreSQLContainer postgres) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
    }
}
