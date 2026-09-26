package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.notification.email.EmailOutboxProcessorJob;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestDatabaseRoles;
import com.peoplehub.support.TestIdentities;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V24's owner-defined functions (b2-8 C3; owner decisions O3, O4, O6): how each is hardened, who
 * may run it, and that each answers only what its flow needs: one organization id or nothing, a new
 * organization's id, or due outbox row ids with their organization. Called as the runtime role, the
 * way the application calls them.
 */
@IntegrationTest
class PreTenantFunctionsTest {

    private static final List<String> RESOLVERS =
            List.of(
                    "peoplehub_organization_by_login_key(text)",
                    "peoplehub_organization_by_refresh_token(text)",
                    "peoplehub_organization_by_verification_token(text)",
                    "peoplehub_organization_by_password_reset_token(text)",
                    "peoplehub_organization_by_invitation_token(text)",
                    "peoplehub_organization_by_mfa_challenge(text)");

    private static final List<String> FUNCTIONS =
            concat(
                    RESOLVERS,
                    List.of(
                            "peoplehub_create_organization(text,text,text)",
                            "peoplehub_email_outbox_reclaim_stale(interval)",
                            "peoplehub_email_outbox_due(integer)"));

    /** A role with no grants at all, to stand for anyone who is not the runtime role. */
    private static final String OUTSIDER = "c3_outsider";

    @Autowired private PostgreSQLContainer postgres;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    @Autowired private LockProvider lockProvider;

    private Connection runtime;
    private SimpleLock outboxJobLock;
    private final List<Long> outboxRows = new ArrayList<>();

    @BeforeEach
    void connectAsRuntimeRole() throws Exception {
        // The scheduled outbox job would otherwise claim this test's outbox rows mid-test: hold
        // its lock, as EmailOutboxProcessorTest does.
        outboxJobLock = holdTheOutboxJobsLock();
        runtime = TestDatabaseRoles.runtimeConnection(postgres);
        jdbc.execute(
                "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
                        + OUTSIDER
                        + "') THEN CREATE ROLE "
                        + OUTSIDER
                        + " NOLOGIN; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + OUTSIDER);
    }

    @AfterEach
    void close() throws SQLException {
        runtime.close();
        if (!outboxRows.isEmpty()) {
            jdbc.update(
                    "DELETE FROM email_outbox WHERE id = ANY(?)",
                    (Object) outboxRows.toArray(Long[]::new));
            outboxRows.clear();
        }
        outboxJobLock.unlock();
    }

    private SimpleLock holdTheOutboxJobsLock() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            Optional<SimpleLock> lock =
                    lockProvider.lock(
                            new LockConfiguration(
                                    Instant.now(),
                                    EmailOutboxProcessorJob.JOB_NAME,
                                    Duration.ofMinutes(5),
                                    Duration.ZERO));
            if (lock.isPresent()) {
                return lock.get();
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the scheduled outbox job never released its lock");
            }
            Thread.sleep(50);
        }
    }

    // ---- hardening ----

    @Test
    void everyFunctionIsSecurityDefinerWithAFixedSearchPathOwnedByTheOwnerRole() {
        for (String function : FUNCTIONS) {
            Map<String, Object> proc =
                    jdbc.queryForMap(
                            "SELECT p.prosecdef, p.proisstrict, p.proconfig::text AS config,"
                                    + " pg_get_userbyid(p.proowner) AS owner"
                                    + " FROM pg_proc p WHERE p.oid = ?::regprocedure",
                            function);
            assertThat(proc)
                    .as(function)
                    .containsEntry("prosecdef", true)
                    .containsEntry("proisstrict", true)
                    .containsEntry("config", "{\"search_path=pg_catalog, pg_temp\"}")
                    .containsEntry("owner", TestDatabaseRoles.OWNER_ROLE);
        }
    }

    @Test
    void onlyTheRuntimeRoleMayExecuteThemNotPublic() {
        for (String function : FUNCTIONS) {
            // Everyone granted EXECUTE other than the owner itself: exactly the runtime role.
            List<String> grantees =
                    jdbc.queryForList(
                            "SELECT CASE WHEN a.grantee = 0 THEN 'PUBLIC'"
                                    + " ELSE pg_get_userbyid(a.grantee) END"
                                    + " FROM pg_proc p, aclexplode(p.proacl) a"
                                    + " WHERE p.oid = ?::regprocedure AND a.privilege_type ="
                                    + " 'EXECUTE' AND a.grantee <> p.proowner",
                            String.class,
                            function);
            assertThat(grantees).as(function).containsExactly(TestDatabaseRoles.RUNTIME_ROLE);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT has_function_privilege(?, ?::regprocedure, 'EXECUTE')",
                                    Boolean.class,
                                    OUTSIDER,
                                    function))
                    .as(function)
                    .isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT peoplehub_organization_by_login_key('x')",
                "SELECT peoplehub_organization_by_refresh_token('x')",
                "SELECT peoplehub_organization_by_verification_token('x')",
                "SELECT peoplehub_organization_by_password_reset_token('x')",
                "SELECT peoplehub_organization_by_invitation_token('x')",
                "SELECT peoplehub_organization_by_mfa_challenge('x')",
                "SELECT peoplehub_create_organization('n', 'k', 'UTC')",
                "SELECT peoplehub_email_outbox_reclaim_stale(interval '5 minutes')",
                "SELECT * FROM peoplehub_email_outbox_due(1)"
            })
    void aRoleWithoutTheGrantCannotCallThem(String call) {
        assertThatThrownBy(
                        () ->
                                jdbc.execute(
                                        (Connection c) -> {
                                            try (Statement s = c.createStatement()) {
                                                s.execute("SET ROLE " + OUTSIDER);
                                                try {
                                                    s.execute(call);
                                                } finally {
                                                    s.execute("RESET ROLE");
                                                }
                                            }
                                            return null;
                                        }))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.INSUFFICIENT_PRIVILEGE));
    }

    @Test
    void eachFunctionAnswersOnlyAnIdOrItsDocumentedColumns() {
        for (String resolver : RESOLVERS) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT format_type(prorettype, NULL) FROM pg_proc"
                                            + " WHERE oid = ?::regprocedure AND NOT proretset",
                                    String.class,
                                    resolver))
                    .as(resolver)
                    .isEqualTo("uuid");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT format_type(prorettype, NULL) FROM pg_proc"
                                        + " WHERE oid = ?::regprocedure",
                                String.class,
                                "peoplehub_create_organization(text,text,text)"))
                .isEqualTo("uuid");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pg_get_function_result(?::regprocedure)",
                                String.class,
                                "peoplehub_email_outbox_due(integer)"))
                .isEqualTo("TABLE(id bigint, organization_id uuid)");
    }

    // ---- resolvers ----

    @Test
    void theLoginKeyResolverAnswersTheExactKeysOrganizationOrNothing() throws SQLException {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);

        assertThat(resolve("peoplehub_organization_by_login_key", org.loginKey()))
                .isEqualTo(org.id());
        assertThat(resolve("peoplehub_organization_by_login_key", other.loginKey()))
                .isEqualTo(other.id());
        assertThat(resolve("peoplehub_organization_by_login_key", "org-" + UUID.randomUUID()))
                .isNull();
        assertThat(resolve("peoplehub_organization_by_login_key", null)).isNull();
    }

    @Test
    void theLoginKeyResolverAnswersWhateverTheOrganizationsStatus() throws SQLException {
        UUID pending =
                jdbc.queryForObject(
                        "INSERT INTO organization (name, login_key_normalized, timezone)"
                                + " VALUES ('Pending', ?, 'UTC') RETURNING id",
                        UUID.class,
                        "pending-" + UUID.randomUUID());
        String key =
                jdbc.queryForObject(
                        "SELECT login_key_normalized FROM organization WHERE id = ?",
                        String.class,
                        pending);

        // The flow decides what PENDING_VERIFICATION means, inside the organization.
        assertThat(resolve("peoplehub_organization_by_login_key", key)).isEqualTo(pending);
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "org-%", "' OR true --", ""})
    void noResolverMatchesPatternsOrInjection(String probe) throws SQLException {
        TestIdentities.Employee employee = seededEmployeeWithEveryToken();
        assertThat(employee).isNotNull();
        for (String resolver : resolverNames()) {
            assertThat(resolve(resolver, probe)).as(resolver + " with " + probe).isNull();
        }
    }

    @Test
    void eachTokenResolverAnswersItsOwnTokensOrganizationOrNothing() throws SQLException {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        Map<String, String> hashes = insertOneTokenOfEachKind(employee);

        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            assertThat(resolve(entry.getKey(), entry.getValue()))
                    .as(entry.getKey())
                    .isEqualTo(org.id());
            assertThat(resolve(entry.getKey(), "unknown-" + UUID.randomUUID()))
                    .as(entry.getKey())
                    .isNull();
            assertThat(resolve(entry.getKey(), null)).as(entry.getKey()).isNull();
            // Each resolver reads only its own table: another kind's hash is unknown to it.
            for (Map.Entry<String, String> other : hashes.entrySet()) {
                if (!other.getKey().equals(entry.getKey())) {
                    assertThat(resolve(entry.getKey(), other.getValue()))
                            .as(entry.getKey() + " given a " + other.getKey() + " hash")
                            .isNull();
                }
            }
        }
    }

    @Test
    void aTokenResolverAnswersEvenForAnExpiredOrUsedTokenSoTheFlowDecides() throws SQLException {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        String hash = "reset-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO password_reset_token (organization_id, employee_id, token_hash,"
                        + " expires_at, consumed_at) VALUES (?, ?, ?, now() - interval '1 hour',"
                        + " now() - interval '2 hours')",
                org.id(),
                employee.id(),
                hash);

        assertThat(resolve("peoplehub_organization_by_password_reset_token", hash))
                .isEqualTo(org.id());
    }

    // ---- organization creation ----

    @Test
    void createOrganizationInsertsOnlyNameKeyAndTimezoneAndReturnsTheGeneratedId()
            throws SQLException {
        String key = "created-" + UUID.randomUUID();
        UUID id;
        try (PreparedStatement ps =
                runtime.prepareStatement("SELECT peoplehub_create_organization(?, ?, ?)")) {
            ps.setString(1, "Created Org");
            ps.setString(2, key);
            ps.setString(3, "Asia/Kolkata");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getObject(1, UUID.class);
            }
        }

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT name, login_key_normalized, timezone, status, mfa_policy,"
                                + " onboarding_completed_at FROM organization WHERE id = ?",
                        id);
        assertThat(row)
                .containsEntry("name", "Created Org")
                .containsEntry("login_key_normalized", key)
                .containsEntry("timezone", "Asia/Kolkata")
                .containsEntry("status", "PENDING_VERIFICATION")
                .containsEntry("mfa_policy", "DISABLED")
                .containsEntry("onboarding_completed_at", null);
    }

    @Test
    void createOrganizationKeepsTheTablesConstraints() throws SQLException {
        String key = "dup-" + UUID.randomUUID();
        callCreate("First", key, "UTC");

        assertThatThrownBy(() -> callCreate("Second", key, "UTC"))
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));
        assertThatThrownBy(() -> callCreate("Bad Key", "  Not Normalized ", "UTC"))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.CHECK_VIOLATION));
    }

    // ---- outbox ----

    @Test
    void theOutboxFunctionsTakeNoTimeFromTheCaller() {
        // The database's clock decides "stale" and "due"; the caller supplies only an age and a
        // limit, both bounded inside the function.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pg_get_function_arguments(?::regprocedure)",
                                String.class,
                                "peoplehub_email_outbox_reclaim_stale(interval)"))
                .isEqualTo("p_stale_after interval");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pg_get_function_arguments(?::regprocedure)",
                                String.class,
                                "peoplehub_email_outbox_due(integer)"))
                .isEqualTo("p_limit integer");
    }

    @Test
    void outboxDiscoveryReturnsOnlyRowsDueNowWithTheirOrganizationAcrossOrganizations()
            throws SQLException {
        UUID first = TestIdentities.activeOrganization(jdbc).id();
        UUID second = TestIdentities.activeOrganization(jdbc).id();
        Instant now = databaseNow();
        long firstDue = outboxRow(first, "PENDING", null, null);
        long secondDue = outboxRow(second, "RETRYING", now.minusSeconds(60), null);
        long notYet = outboxRow(second, "RETRYING", now.plusSeconds(3600), null);
        long sent = outboxRow(first, "SENT", null, null);

        List<Long> ids = new ArrayList<>();
        List<UUID> organizations = new ArrayList<>();
        try (PreparedStatement ps =
                runtime.prepareStatement("SELECT * FROM peoplehub_email_outbox_due(?)")) {
            ps.setInt(1, 1000);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.getMetaData().getColumnCount()).isEqualTo(2);
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                    organizations.add(rs.getObject("organization_id", UUID.class));
                }
            }
        }

        assertThat(ids).contains(firstDue, secondDue).doesNotContain(notYet, sent);
        assertThat(organizations.get(ids.indexOf(firstDue))).isEqualTo(first);
        assertThat(organizations.get(ids.indexOf(secondDue))).isEqualTo(second);
        assertThat(ids).isSorted();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001})
    void outboxDiscoveryRefusesALimitOutsideOneToAThousand(int limit) {
        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement ps =
                                    runtime.prepareStatement(
                                            "SELECT * FROM peoplehub_email_outbox_due(?)")) {
                                ps.setInt(1, limit);
                                ps.executeQuery().close();
                            }
                        })
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("22023"));
    }

    @Test
    void outboxReclaimResetsOnlyStaleSendingRowsAndOnlyTheirStatus() throws SQLException {
        UUID first = TestIdentities.activeOrganization(jdbc).id();
        UUID second = TestIdentities.activeOrganization(jdbc).id();
        Instant now = databaseNow();
        long staleFirst = outboxRow(first, "SENDING", null, now.minusSeconds(3600));
        long staleSecond = outboxRow(second, "SENDING", null, now.minusSeconds(3600));
        long fresh = outboxRow(first, "SENDING", null, now.minusSeconds(60));
        long pending = outboxRow(second, "PENDING", null, now.minusSeconds(3600));
        Map<String, Object> before = outboxState(staleFirst);

        int reclaimed = reclaim("5 minutes");

        assertThat(reclaimed).isGreaterThanOrEqualTo(2);
        assertThat(outboxState(staleFirst).get("status")).isEqualTo("RETRYING");
        assertThat(outboxState(staleSecond).get("status")).isEqualTo("RETRYING");
        assertThat(outboxState(fresh).get("status")).isEqualTo("SENDING");
        assertThat(outboxState(pending).get("status")).isEqualTo("PENDING");
        Map<String, Object> after = new HashMap<>(outboxState(staleFirst));
        after.put("status", before.get("status"));
        assertThat(after).as("nothing but the status changed").isEqualTo(before);
    }

    @Test
    void aCallerCannotForceAnInFlightMessageToBeReclaimed() throws SQLException {
        UUID org = TestIdentities.activeOrganization(jdbc).id();
        // Claimed 30 seconds ago by the database's clock: another worker is sending it.
        long inFlight = outboxRow(org, "SENDING", null, databaseNow().minusSeconds(30));

        // The shortest age the function accepts is still longer than the claim is old.
        reclaim("1 minute");
        assertThat(outboxState(inFlight).get("status")).isEqualTo("SENDING");

        // No age can move the cut-off to "now" or past it: shorter, zero and negative ages (the
        // equivalent of a caller's future clock value) are refused, and so is one over a day;
        // nothing changes.
        for (String age :
                List.of("59 seconds", "0 seconds", "-1 hour", "-100 years", "1 day 1 second")) {
            assertThatThrownBy(() -> reclaim(age))
                    .as(age)
                    .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("22023"));
        }
        assertThat(outboxState(inFlight).get("status")).isEqualTo("SENDING");
    }

    @Test
    void aCallerCannotMakeARetryScheduledForLaterDueEarly() throws SQLException {
        UUID org = TestIdentities.activeOrganization(jdbc).id();
        long later = outboxRow(org, "RETRYING", databaseNow().plusSeconds(3600), null);

        // The only argument is the limit: "due" is always the database's now().
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps =
                        runtime.prepareStatement(
                                "SELECT id FROM peoplehub_email_outbox_due(1000)");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        assertThat(ids).doesNotContain(later);
    }

    private int reclaim(String age) throws SQLException {
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT peoplehub_email_outbox_reclaim_stale(CAST(? AS interval))")) {
            ps.setString(1, age);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    // ---- helpers ----

    private UUID resolve(String function, String value) throws SQLException {
        try (PreparedStatement ps = runtime.prepareStatement("SELECT " + function + "(?)")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                UUID answer = rs.getObject(1, UUID.class);
                assertThat(rs.next()).as("one row, one value").isFalse();
                return answer;
            }
        }
    }

    private void callCreate(String name, String key, String timezone) throws SQLException {
        try (PreparedStatement ps =
                runtime.prepareStatement("SELECT peoplehub_create_organization(?, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, key);
            ps.setString(3, timezone);
            ps.executeQuery().close();
        }
    }

    private static List<String> resolverNames() {
        return RESOLVERS.stream().map(r -> r.substring(0, r.indexOf('('))).toList();
    }

    private TestIdentities.Employee seededEmployeeWithEveryToken() {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        insertOneTokenOfEachKind(employee);
        return employee;
    }

    /** One row of each token table for the employee; returns resolver name to token hash. */
    private Map<String, String> insertOneTokenOfEachKind(TestIdentities.Employee employee) {
        UUID org = employee.organizationId();
        String refresh = "refresh-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO refresh_token (organization_id, employee_id, token_hash, family_id,"
                        + " expires_at, absolute_expires_at) VALUES (?, ?, ?, ?,"
                        + " now() + interval '1 day', now() + interval '2 days')",
                org,
                employee.id(),
                refresh,
                UUID.randomUUID());
        String verification = "verify-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organization_verification_token (organization_id, token_hash,"
                        + " expires_at) VALUES (?, ?, now() + interval '1 day')",
                org,
                verification);
        String reset = "reset-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO password_reset_token (organization_id, employee_id, token_hash,"
                        + " expires_at) VALUES (?, ?, ?, now() + interval '1 hour')",
                org,
                employee.id(),
                reset);
        String invitation = "invite-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO employee_invitation (organization_id, email_normalized,"
                        + " intended_role, token_hash, inviter_employee_id, expires_at)"
                        + " VALUES (?, ?, 'EMPLOYEE', ?, ?, now() + interval '1 day')",
                org,
                "invitee-" + UUID.randomUUID() + "@example.com",
                invitation,
                employee.id());
        String challenge = "challenge-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO mfa_challenge (organization_id, employee_id, token_hash, purpose,"
                        + " expires_at) VALUES (?, ?, ?, 'CHALLENGE', now() + interval '5 minutes')",
                org,
                employee.id(),
                challenge);
        return Map.of(
                "peoplehub_organization_by_refresh_token", refresh,
                "peoplehub_organization_by_verification_token", verification,
                "peoplehub_organization_by_password_reset_token", reset,
                "peoplehub_organization_by_invitation_token", invitation,
                "peoplehub_organization_by_mfa_challenge", challenge);
    }

    private long outboxRow(UUID org, String status, Instant nextAttemptAt, Instant lastAttemptAt) {
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO email_outbox (organization_id, recipient, type, payload, status,"
                                + " next_attempt_at, last_attempt_at)"
                                + " VALUES (?, ?, 'EMPLOYEE_INVITED', '{}'::jsonb, ?, ?, ?) RETURNING id",
                        Long.class,
                        org,
                        "c3-" + UUID.randomUUID() + "@example.com",
                        status,
                        nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
                        lastAttemptAt == null ? null : Timestamp.from(lastAttemptAt));
        outboxRows.add(id);
        return id;
    }

    private Map<String, Object> outboxState(long id) {
        return jdbc.queryForMap("SELECT * FROM email_outbox WHERE id = ?", id);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
