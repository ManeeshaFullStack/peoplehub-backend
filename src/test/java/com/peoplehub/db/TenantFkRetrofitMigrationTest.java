package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.TestDatabaseRoles;
import com.peoplehub.support.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V12 (tenant FK retrofit): proves both directions of adding {@code organization_id} foreign keys
 * to four already-shipped tables (b2-1, Spec 2.1.1, 12.1) -- valid pre-existing data survives the
 * migration untouched, and an orphaned {@code organization_id} makes the migration fail outright,
 * rather than silently succeeding over bad data.
 *
 * <p>This needs a database migrated only up to V11, with a row deliberately inserted before V12 can
 * add the constraint that would otherwise reject it -- something the normal
 * {@code @IntegrationTest} context (which always migrates to the latest version before the test
 * even starts) cannot express. So this test drives {@link Flyway} directly, targeting a specific
 * version, against its own container -- the same "manage the database lifecycle by hand" style
 * {@code TwoRoleWiringTest} already uses, just with Flyway instead of the full application.
 */
class TenantFkRetrofitMigrationTest {

    private PostgreSQLContainer postgres;

    @AfterEach
    void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private PostgreSQLContainer startContainer() {
        postgres = TestcontainersConfiguration.newPostgresContainer();
        postgres.start();
        return postgres;
    }

    private static Flyway flywayTargeting(PostgreSQLContainer postgres, String target) {
        DataSource dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        return Flyway.configure()
                .dataSource(dataSource)
                .placeholders(Map.of("runtime_role", TestDatabaseRoles.RUNTIME_ROLE))
                .target(target)
                .load();
    }

    private static Connection superuser(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void existingValidRowsSurviveTheRetrofitUntouched() throws SQLException {
        PostgreSQLContainer container = startContainer();
        flywayTargeting(container, "11").migrate();

        UUID org;
        UUID auditRowOrg;
        try (Connection c = superuser(container);
                Statement s = c.createStatement()) {
            org =
                    UUID.fromString(
                            SqlOneColumn.queryOne(
                                    c,
                                    "INSERT INTO organization (name, login_key_normalized,"
                                            + " timezone) VALUES ('Acme Corp', 'acme-corp',"
                                            + " 'Asia/Kolkata') RETURNING id"));
            s.execute(
                    "INSERT INTO audit_log (organization_id, actor_id, action) VALUES ('"
                            + org
                            + "', 'job:test', 'SOMETHING_HAPPENED')");
            auditRowOrg = org;
        }

        flywayTargeting(container, "12").migrate();

        try (Connection c = superuser(container)) {
            String stillThere =
                    SqlOneColumn.queryOne(
                            c,
                            "SELECT count(*) FROM audit_log WHERE organization_id = '"
                                    + auditRowOrg
                                    + "'");
            assertThat(stillThere).isEqualTo("1");
            Integer fkCount =
                    Integer.valueOf(
                            SqlOneColumn.queryOne(
                                    c,
                                    "SELECT count(*) FROM pg_constraint WHERE conname ="
                                            + " 'fk_audit_log_organization'"));
            assertThat(fkCount).isEqualTo(1);
        }
    }

    @Test
    void anOrphanedOrganizationIdMakesTheMigrationFailOutright() throws SQLException {
        PostgreSQLContainer container = startContainer();
        flywayTargeting(container, "11").migrate();

        // Only possible before V12 exists: no FK yet, so an unresolvable organization_id can still
        // be inserted (the not-nil CHECK from V4 only rejects the literal nil UUID, not an
        // unresolvable one).
        UUID orphan = UUID.randomUUID();
        try (Connection c = superuser(container);
                Statement s = c.createStatement()) {
            s.execute(
                    "INSERT INTO email_outbox (organization_id, recipient, type) VALUES ('"
                            + orphan
                            + "', 'jane@example.com', 'SOMETHING_HAPPENED')");
        }

        assertThatThrownBy(() -> flywayTargeting(container, "12").migrate())
                .isInstanceOf(FlywayException.class);

        // The failed migration is rolled back and not recorded as successful.
        try (Connection c = superuser(container)) {
            String failedCount =
                    SqlOneColumn.queryOne(
                            c,
                            "SELECT count(*) FROM flyway_schema_history WHERE version = '12'"
                                    + " AND success");
            assertThat(failedCount).isEqualTo("0");
            String fkExists =
                    SqlOneColumn.queryOne(
                            c,
                            "SELECT count(*) FROM pg_constraint WHERE conname ="
                                    + " 'fk_email_outbox_organization'");
            assertThat(fkExists).isEqualTo("0");
        }
    }

    /** A tiny local helper: one column, one row, as plain JDBC (no Spring context here). */
    private static final class SqlOneColumn {
        static String queryOne(Connection c, String sql) throws SQLException {
            try (Statement s = c.createStatement();
                    var rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
