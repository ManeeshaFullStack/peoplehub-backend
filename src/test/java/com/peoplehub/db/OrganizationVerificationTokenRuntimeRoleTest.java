package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestDatabaseRoles;
import com.peoplehub.support.TestOrganizations;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the least-privileged runtime role can and cannot do to {@code
 * organization_verification_token} (b2-2, Spec 2.1.3, B2-2/5), against real PostgreSQL and
 * connected as that role. Mirrors {@code OrganizationRuntimeRoleTest}'s coverage of the equivalent
 * V8 table.
 *
 * <p>Like {@code EmployeeRuntimeRoleTest}, this table has a real FK to {@code organization}, so its
 * fixture organization row is inserted through the superuser-backed {@link JdbcTemplate}, not
 * {@link TestDatabaseRoles#ownerConnection} (B0-6/3: most tests keep the container's own superuser
 * for both Flyway and the app).
 */
@IntegrationTest
class OrganizationVerificationTokenRuntimeRoleTest {

    @Autowired private PostgreSQLContainer postgres;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    private Connection runtime;

    /** Binds this test's runtime connection to the organization it has just created (V25). */
    private UUID bound(UUID organizationId) {
        TestDatabaseRoles.bindTenant(runtime, organizationId);
        return organizationId;
    }

    @BeforeEach
    void connectAsRuntimeRole() throws SQLException {
        runtime = TestDatabaseRoles.runtimeConnection(postgres);
    }

    @AfterEach
    void close() throws SQLException {
        runtime.close();
    }

    private void assertDenied(String sql) {
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(sql);
                            }
                        })
                .as(sql)
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.INSUFFICIENT_PRIVILEGE))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        .containsAnyOf("permission denied", "must be owner"));
    }

    @Test
    void insertOnOrganizationIdTokenHashAndExpiresAtSucceedsAndSelectSucceeds()
            throws SQLException {
        UUID org = bound(TestOrganizations.insert(jdbc));
        String hash = "hash-" + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO organization_verification_token (organization_id,"
                                + " token_hash, expires_at) VALUES (?, ?, ?)")) {
            ps.setObject(1, org);
            ps.setString(2, hash);
            ps.setTimestamp(3, Timestamp.from(expiresAt));
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT id, consumed_at FROM organization_verification_token WHERE"
                                + " token_hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("id")).isNotNull();
                assertThat(rs.getTimestamp("consumed_at")).isNull();
            }
        }
    }

    @Test
    void idAndCreatedAtCannotBeSuppliedOnInsertEvenThoughTheyHaveDefaults() {
        UUID org = bound(TestOrganizations.insert(jdbc));
        assertDenied(
                "INSERT INTO organization_verification_token (id, organization_id, token_hash,"
                        + " expires_at) VALUES (gen_random_uuid(), '"
                        + org
                        + "', 'hash-"
                        + UUID.randomUUID()
                        + "', now() + interval '24 hours')");
        assertDenied(
                "INSERT INTO organization_verification_token (organization_id, token_hash,"
                        + " expires_at, created_at) VALUES ('"
                        + org
                        + "', 'hash-"
                        + UUID.randomUUID()
                        + "', now() + interval '24 hours', now())");
    }

    @Test
    void consumedAtCanBeUpdated() throws SQLException {
        UUID org = bound(TestOrganizations.insert(jdbc));
        String hash = "hash-" + UUID.randomUUID();
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO organization_verification_token (organization_id,"
                                + " token_hash, expires_at) VALUES (?, ?, now() + interval '24"
                                + " hours')")) {
            ps.setObject(1, org);
            ps.setString(2, hash);
            ps.executeUpdate();
        }

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE organization_verification_token SET consumed_at = now() WHERE"
                                + " token_hash = ? AND consumed_at IS NULL")) {
            ps.setString(1, hash);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    @Test
    void organizationIdAndTokenHashCannotBeUpdated() {
        // Bound to some tenant (V25), so what refuses these is the missing privilege.
        bound(UUID.randomUUID());
        assertDenied(
                "UPDATE organization_verification_token SET organization_id = gen_random_uuid()");
        assertDenied("UPDATE organization_verification_token SET token_hash = 'someone-else'");
    }

    @Test
    void deleteAndTruncateAreDenied() throws SQLException {
        UUID org = bound(TestOrganizations.insert(jdbc));
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO organization_verification_token (organization_id,"
                                + " token_hash, expires_at) VALUES (?, ?, now() + interval '24"
                                + " hours')")) {
            ps.setObject(1, org);
            ps.setString(2, "hash-" + UUID.randomUUID());
            ps.executeUpdate();
        }

        assertDenied("DELETE FROM organization_verification_token");
        assertDenied("TRUNCATE organization_verification_token");
    }

    @Test
    void grantsNothingToPublic() throws SQLException {
        try (Statement s = runtime.createStatement();
                ResultSet acl =
                        s.executeQuery(
                                "SELECT unnest(relacl)::text FROM pg_class WHERE oid ="
                                        + " 'public.organization_verification_token'::regclass")) {
            while (acl.next()) {
                assertThat(acl.getString(1)).doesNotStartWith("=");
            }
        }
    }
}
