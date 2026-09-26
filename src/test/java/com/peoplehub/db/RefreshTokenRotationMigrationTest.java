package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestDatabaseRoles;
import com.peoplehub.support.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V14 (refresh_token rotation, b2-3, B2-3 implementation plan): the tenant binding and the
 * revocation columns reject bad data. Runs as the container's superuser; {@code
 * RefreshTokenRuntimeRoleTest} covers what the runtime role may do. Every test creates its own rows
 * (no table-wide cleanup, same reason as {@code RefreshTokenMigrationTest}).
 */
@IntegrationTest
class RefreshTokenRotationMigrationTest {

    private static final String INSERT =
            "INSERT INTO refresh_token (organization_id, employee_id, token_hash, family_id,"
                    + " expires_at, absolute_expires_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING id";

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    private UUID insertOrganization() {
        return jdbc.queryForObject(
                "INSERT INTO organization (name, login_key_normalized, timezone) VALUES (?, ?, ?)"
                        + " RETURNING id",
                UUID.class,
                "Acme Corp",
                "org-" + UUID.randomUUID(),
                "Asia/Kolkata");
    }

    private UUID insertEmployee(UUID org) {
        String email = "jane-" + UUID.randomUUID() + "@example.com";
        return jdbc.queryForObject(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, role) VALUES (?, ?, 'Jane Doe', ?, ?, 'EMPLOYEE')"
                        + " RETURNING id",
                UUID.class,
                org,
                "E-" + UUID.randomUUID(),
                email,
                email);
    }

    private static Timestamp in(int amount, ChronoUnit unit) {
        return Timestamp.from(Instant.now().plus(amount, unit));
    }

    private UUID insertToken(UUID org, UUID employee) {
        return jdbc.queryForObject(
                INSERT,
                UUID.class,
                org,
                employee,
                "hash-" + UUID.randomUUID(),
                UUID.randomUUID(),
                in(30, ChronoUnit.DAYS),
                in(90, ChronoUnit.DAYS));
    }

    private static void assertViolates(Runnable statement, String sqlState) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(sqlState));
    }

    private static void assertViolatesCheck(Runnable statement, String constraint) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(SqlErrors.CHECK_VIOLATION))
                .message()
                .contains(constraint);
    }

    @Test
    void v14AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '14' AND"
                                + " success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void organizationIdIsRequired() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertViolates(
                () ->
                        jdbc.update(
                                "INSERT INTO refresh_token (employee_id, token_hash, family_id,"
                                        + " expires_at, absolute_expires_at) VALUES (?, ?, ?, ?,"
                                        + " ?)",
                                employee,
                                "hash-" + UUID.randomUUID(),
                                UUID.randomUUID(),
                                in(30, ChronoUnit.DAYS),
                                in(90, ChronoUnit.DAYS)),
                SqlErrors.NOT_NULL_VIOLATION);
    }

    @Test
    void aTokenCannotNameAnEmployeeOfAnotherOrganization() {
        UUID orgA = insertOrganization();
        UUID orgB = insertOrganization();
        UUID employeeOfB = insertEmployee(orgB);

        assertViolates(() -> insertToken(orgA, employeeOfB), "23503");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE conname ="
                                        + " 'fk_refresh_token_employee_in_organization'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theEmployeeTableHasTheCompositeKeyTheForeignKeyNeeds() {
        String definition =
                jdbc.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname ="
                                + " 'uq_employee_organization_id'",
                        String.class);

        assertThat(definition).isEqualTo("UNIQUE (organization_id, id)");
    }

    @Test
    void aNewTokenIsNotRevokedAndHasNoRevocationDetails() {
        UUID org = insertOrganization();
        UUID id = insertToken(org, insertEmployee(org));

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT revoked, revoked_at, revoke_reason, replaced_by_id"
                                + " FROM refresh_token WHERE id = ?",
                        id);
        assertThat(row.get("revoked")).isEqualTo(false);
        assertThat(row.get("revoked_at")).isNull();
        assertThat(row.get("revoke_reason")).isNull();
        assertThat(row.get("replaced_by_id")).isNull();
    }

    @Test
    void aRevokedTokenNeedsATimeAndAReason() {
        UUID org = insertOrganization();
        UUID id = insertToken(org, insertEmployee(org));

        assertViolatesCheck(
                () -> jdbc.update("UPDATE refresh_token SET revoked = true WHERE id = ?", id),
                "ck_refresh_token_revocation_consistent");
        assertViolatesCheck(
                () ->
                        jdbc.update(
                                "UPDATE refresh_token SET revoked = true, revoked_at = now()"
                                        + " WHERE id = ?",
                                id),
                "ck_refresh_token_revocation_consistent");
        assertViolatesCheck(
                () ->
                        jdbc.update(
                                "UPDATE refresh_token SET revoke_reason = 'LOGOUT' WHERE id = ?",
                                id),
                "ck_refresh_token_revocation_consistent");

        jdbc.update(
                "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                        + " revoke_reason = 'LOGOUT' WHERE id = ?",
                id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoke_reason FROM refresh_token WHERE id = ?",
                                String.class,
                                id))
                .isEqualTo("LOGOUT");
    }

    @Test
    void onlyTheKnownRevokeReasonsAreAccepted() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        // V14's three reasons, plus PASSWORD_RESET and PASSWORD_CHANGED since V16 (b2-5), and
        // SESSION_REVOKED and DEACTIVATED since V18 (b2-6).
        for (String reason :
                new String[] {
                    "ROTATED",
                    "LOGOUT",
                    "REUSE_DETECTED",
                    "PASSWORD_RESET",
                    "PASSWORD_CHANGED",
                    "SESSION_REVOKED",
                    "DEACTIVATED"
                }) {
            UUID id = insertToken(org, employee);
            jdbc.update(
                    "UPDATE refresh_token SET revoked = true, revoked_at = now(), revoke_reason = ?"
                            + " WHERE id = ?",
                    reason,
                    id);
        }
        UUID id = insertToken(org, employee);
        assertViolatesCheck(
                () ->
                        jdbc.update(
                                "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                                        + " revoke_reason = 'EXPIRED' WHERE id = ?",
                                id),
                "ck_refresh_token_revoke_reason");
    }

    @Test
    void replacedByMustBeARealTokenAndOnlyOnRotation() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        UUID old = insertToken(org, employee);
        UUID successor = insertToken(org, employee);

        assertViolates(
                () ->
                        jdbc.update(
                                "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                                        + " revoke_reason = 'ROTATED', replaced_by_id = ?"
                                        + " WHERE id = ?",
                                UUID.randomUUID(),
                                old),
                "23503");
        assertViolatesCheck(
                () ->
                        jdbc.update(
                                "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                                        + " revoke_reason = 'LOGOUT', replaced_by_id = ?"
                                        + " WHERE id = ?",
                                successor,
                                old),
                "ck_refresh_token_replaced_only_when_rotated");

        jdbc.update(
                "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                        + " revoke_reason = 'ROTATED', replaced_by_id = ? WHERE id = ?",
                successor,
                old);
    }

    @Test
    void theSlidingExpiryCannotPassTheAbsoluteLimit() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertViolatesCheck(
                () ->
                        jdbc.update(
                                INSERT.replace(" RETURNING id", ""),
                                org,
                                employee,
                                "hash-" + UUID.randomUUID(),
                                UUID.randomUUID(),
                                in(91, ChronoUnit.DAYS),
                                in(90, ChronoUnit.DAYS)),
                "ck_refresh_token_expiry_within_absolute");
    }

    /**
     * A row written before V14 gets its organization from its employee. Needs a database migrated
     * only to V13 with a row inserted before V14 runs, so it drives Flyway directly against its own
     * container (the {@code TenantFkRetrofitMigrationTest} pattern).
     */
    @Test
    void anExistingTokenIsBackfilledWithItsEmployeesOrganization() throws SQLException {
        PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgresContainer();
        postgres.start();
        try {
            flywayTargeting(postgres, "13").migrate();

            String org;
            String token;
            try (Connection c = superuser(postgres)) {
                org =
                        queryOne(
                                c,
                                "INSERT INTO organization (name, login_key_normalized, timezone)"
                                        + " VALUES ('Acme Corp', 'acme-corp', 'Asia/Kolkata')"
                                        + " RETURNING id");
                String employee =
                        queryOne(
                                c,
                                "INSERT INTO employee (organization_id, employee_code, name,"
                                        + " email, email_normalized, role) VALUES ('"
                                        + org
                                        + "', 'E-1', 'Jane Doe', 'jane@example.com',"
                                        + " 'jane@example.com', 'EMPLOYEE') RETURNING id");
                token =
                        queryOne(
                                c,
                                "INSERT INTO refresh_token (employee_id, token_hash, family_id,"
                                        + " expires_at, absolute_expires_at) VALUES ('"
                                        + employee
                                        + "', 'hash-1', gen_random_uuid(), now() + interval '30"
                                        + " days', now() + interval '90 days') RETURNING id");
            }

            flywayTargeting(postgres, "14").migrate();

            try (Connection c = superuser(postgres)) {
                assertThat(
                                queryOne(
                                        c,
                                        "SELECT organization_id FROM refresh_token WHERE id = '"
                                                + token
                                                + "'"))
                        .isEqualTo(org);
            }
        } finally {
            postgres.stop();
        }
    }

    private static Flyway flywayTargeting(PostgreSQLContainer postgres, String target) {
        return Flyway.configure()
                .dataSource(
                        new DriverManagerDataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword()))
                .placeholders(Map.of("runtime_role", TestDatabaseRoles.RUNTIME_ROLE))
                .target(target)
                .load();
    }

    private static Connection superuser(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String queryOne(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
