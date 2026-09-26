package com.peoplehub.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The database roles the test containers are provisioned with ({@code
 * testcontainers/db-roles.sql}), and connections as each of them (B0-6/3).
 *
 * <p>The shared integration-test context connects the application as {@link #RUNTIME_ROLE} and runs
 * Flyway as {@link #OWNER_ROLE} ({@link TestcontainersConfiguration}); test fixtures use the
 * container's superuser only through {@link PrivilegedFixture}. The connections below are for tests
 * that exercise one role directly.
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

    /**
     * Binds a test's own, unpooled runtime-role connection to {@code organizationId} for the rest
     * of its session (b2-8 C4, V25), so the tenant policies let it act on that organization's rows.
     * Test-only, and only for a connection the test opened itself and closes: the application never
     * sets the tenant for a session, only for a transaction (O1). Pass {@code null} to unbind.
     */
    public static void bindTenant(Connection runtime, UUID organizationId) {
        try (PreparedStatement statement =
                runtime.prepareStatement(
                        "SELECT set_config('peoplehub.organization_id', ?, false)")) {
            statement.setString(1, organizationId == null ? "" : organizationId.toString());
            statement.executeQuery().close();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not bind the test connection's tenant", e);
        }
    }

    public static Connection ownerConnection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), OWNER_ROLE, OWNER_PASSWORD);
    }

    /**
     * Command-line arguments for an application started programmatically against {@code postgres}:
     * the application connects as the runtime role and Flyway migrates as the owner role, the same
     * as the shared integration-test context ({@link TestcontainersConfiguration}).
     */
    public static List<String> applicationDatabaseArguments(PostgreSQLContainer postgres) {
        return List.of(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + RUNTIME_ROLE,
                "--spring.datasource.password=" + RUNTIME_PASSWORD,
                "--spring.flyway.user=" + OWNER_ROLE,
                "--spring.flyway.password=" + OWNER_PASSWORD);
    }

    /**
     * A fixture template connected as the container's superuser, for tests that start their own
     * application and so have no {@link PrivilegedFixture} bean. Never hand it to the application.
     */
    public static JdbcTemplate privilegedFixtureJdbc(PostgreSQLContainer postgres) {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    /** A DataSource that opens a new connection per use, as the least-privileged role. */
    public static DataSource runtimeDataSource(PostgreSQLContainer postgres) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
    }
}
