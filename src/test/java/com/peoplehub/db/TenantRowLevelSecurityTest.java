package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestDatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V25's row-level security (b2-8 C4; owner decisions O1, O5, O8, O9; Spec D22, D30): which tables
 * carry it, the fail-loud tenant helper, and, for every one of the 13 tenant tables, that the
 * runtime role reads, changes and removes only the bound organization's rows and can neither insert
 * a row for another organization nor move a row to one.
 *
 * <p>Two ways of acting as the runtime role:
 *
 * <ul>
 *   <li>its own connection, with exactly its production grants, for the missing-tenant checks;
 *   <li>for the policy checks, the superuser's connection inside one transaction that is always
 *       rolled back: it grants the table to the runtime role, then {@code SET LOCAL ROLE}s to it
 *       and binds a tenant. The temporary grant takes column privileges out of the way, so what
 *       allows or stops each statement is the policy alone; nothing of it outlives the transaction.
 * </ul>
 */
@IntegrationTest
class TenantRowLevelSecurityTest {

    private static final String RUNTIME = TestDatabaseRoles.RUNTIME_ROLE;
    private static final String NO_TENANT = "PH001";
    private static final String RLS_VIOLATION = SqlErrors.INSUFFICIENT_PRIVILEGE;

    private static final List<String> TENANT_TABLES =
            List.of(
                    "organization",
                    "employee",
                    "employee_invitation",
                    "refresh_token",
                    "mfa_recovery_code",
                    "mfa_challenge",
                    "session_step_up",
                    "organization_verification_token",
                    "password_reset_token",
                    "audit_log",
                    "email_outbox",
                    "notification",
                    "notification_preference");

    private static final List<String> OUTSIDE_RLS =
            List.of("login_attempt", "email_suppression", "shedlock", "flyway_schema_history");

    /**
     * How to add one row for an organization ({@code :org}) and its employee ({@code :emp}),
     * returning the row's locator as text; {@code :u} is a fresh unique value.
     */
    private static final Map<String, String> INSERT_ROW =
            Map.ofEntries(
                    Map.entry(
                            "organization",
                            "INSERT INTO organization (name, login_key_normalized, timezone)"
                                    + " VALUES ('RLS org', 'rls-' || :u, 'UTC') RETURNING id::text"),
                    Map.entry(
                            "employee",
                            "INSERT INTO employee (organization_id, employee_code, name, email,"
                                    + " email_normalized, status, role, join_date) VALUES (:org,"
                                    + " 'E-' || :u, 'Rls Row', :u || '@x.io', :u || '@x.io',"
                                    + " 'ACTIVE', 'EMPLOYEE', DATE '2026-01-05') RETURNING id::text"),
                    Map.entry(
                            "employee_invitation",
                            "INSERT INTO employee_invitation (organization_id, email_normalized,"
                                    + " intended_role, token_hash, inviter_employee_id, expires_at)"
                                    + " VALUES (:org, 'i-' || :u || '@x.io', 'EMPLOYEE', 'h-' || :u,"
                                    + " :emp, now() + interval '1 day') RETURNING id::text"),
                    Map.entry(
                            "refresh_token",
                            "INSERT INTO refresh_token (organization_id, employee_id, token_hash,"
                                    + " family_id, expires_at, absolute_expires_at) VALUES (:org,"
                                    + " :emp, 'h-' || :u, gen_random_uuid(), now() + interval '1"
                                    + " day', now() + interval '2 days') RETURNING id::text"),
                    Map.entry(
                            "mfa_recovery_code",
                            "INSERT INTO mfa_recovery_code (organization_id, employee_id,"
                                    + " code_hash) VALUES (:org, :emp, 'h-' || :u) RETURNING"
                                    + " id::text"),
                    Map.entry(
                            "mfa_challenge",
                            "INSERT INTO mfa_challenge (organization_id, employee_id, token_hash,"
                                    + " purpose, expires_at) VALUES (:org, :emp, 'h-' || :u,"
                                    + " 'CHALLENGE', now() + interval '5 minutes') RETURNING"
                                    + " id::text"),
                    Map.entry(
                            "session_step_up",
                            "INSERT INTO session_step_up (organization_id, employee_id,"
                                    + " session_id, method) VALUES (:org, :emp, gen_random_uuid(),"
                                    + " 'PASSWORD') RETURNING id::text"),
                    Map.entry(
                            "organization_verification_token",
                            "INSERT INTO organization_verification_token (organization_id,"
                                    + " token_hash, expires_at) VALUES (:org, 'h-' || :u, now() +"
                                    + " interval '1 day') RETURNING id::text"),
                    Map.entry(
                            "password_reset_token",
                            "INSERT INTO password_reset_token (organization_id, employee_id,"
                                    + " token_hash, expires_at) VALUES (:org, :emp, 'h-' || :u,"
                                    + " now() + interval '1 hour') RETURNING id::text"),
                    Map.entry(
                            "audit_log",
                            "INSERT INTO audit_log (organization_id, actor_id, action) VALUES"
                                    + " (:org, 'job:rls-test', 'RLS_TEST') RETURNING id::text"),
                    Map.entry(
                            "email_outbox",
                            "INSERT INTO email_outbox (organization_id, recipient, type, payload,"
                                    + " status) VALUES (:org, :u || '@x.io', 'EMPLOYEE_INVITED',"
                                    + " '{}'::jsonb, 'SENT') RETURNING id::text"),
                    Map.entry(
                            "notification",
                            "INSERT INTO notification (organization_id, employee_id, type,"
                                    + " payload) VALUES (:org, :emp, 'RLS_TEST', '{}'::jsonb)"
                                    + " RETURNING id::text"),
                    Map.entry(
                            "notification_preference",
                            "INSERT INTO notification_preference (organization_id, employee_id,"
                                    + " type, email, in_app) VALUES (:org, :emp, 'RLS_' ||"
                                    + " upper(replace(:u, '-', '_')), true, true) RETURNING type"));

    @Autowired private PostgreSQLContainer postgres;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    static Stream<String> tenantTables() {
        return TENANT_TABLES.stream();
    }

    /** The tenant key: the row's own id for organization, organization_id for everything else. */
    private static String keyOf(String table) {
        return table.equals("organization") ? "id" : "organization_id";
    }

    /** The column a row is found by: its id, or its type for notification_preference. */
    private static String locatorOf(String table) {
        return table.equals("notification_preference") ? "type" : "id";
    }

    // ---- inventory and roles ----

    @Test
    void exactlyTheThirteenTenantTablesHaveRowLevelSecurityAndNoneIsForced() {
        List<String> enabled =
                jdbc.queryForList(
                        "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid ="
                                + " c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'r'"
                                + " AND c.relrowsecurity ORDER BY c.relname",
                        String.class);
        assertThat(enabled).containsExactlyInAnyOrderElementsOf(TENANT_TABLES);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid ="
                                        + " c.relnamespace WHERE n.nspname = 'public'"
                                        + " AND c.relforcerowsecurity",
                                Long.class))
                .as("FORCE ROW LEVEL SECURITY on no table (O8)")
                .isZero();
        for (String table : OUTSIDE_RLS) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT relrowsecurity FROM pg_class WHERE oid = ?::regclass",
                                    Boolean.class,
                                    "public." + table))
                    .as(table + " stays outside row-level security")
                    .isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("tenantTables")
    void eachTenantTableHasExactlyTheOneTenantPolicyForTheRuntimeRole(String table) {
        List<Map<String, Object>> policies =
                jdbc.queryForList(
                        "SELECT policyname, permissive, roles::text AS roles, cmd, qual,"
                                + " with_check FROM pg_policies WHERE schemaname = 'public'"
                                + " AND tablename = ?",
                        table);
        assertThat(policies).hasSize(1);
        Map<String, Object> policy = policies.get(0);
        String expected = "(" + keyOf(table) + " = peoplehub_current_organization_id())";
        assertThat(policy)
                .containsEntry("policyname", "tenant_isolation")
                .containsEntry("permissive", "PERMISSIVE")
                .containsEntry("roles", "{" + RUNTIME + "}")
                .containsEntry("cmd", "ALL")
                .containsEntry("qual", expected)
                .containsEntry("with_check", expected);
    }

    @Test
    void theRuntimeRoleIsNeitherSuperuserNorBypassRlsNorAnOwner() {
        Map<String, Object> role =
                jdbc.queryForMap(
                        "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?", RUNTIME);
        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
        for (String table : TENANT_TABLES) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT pg_get_userbyid(relowner) FROM pg_class"
                                            + " WHERE oid = ?::regclass",
                                    String.class,
                                    "public." + table))
                    .as("owner of " + table)
                    .isEqualTo(TestDatabaseRoles.OWNER_ROLE);
        }
    }

    // ---- the helper ----

    @Test
    void theHelperIsNarrowAndOnlyTheRuntimeRoleMayCallIt() {
        Map<String, Object> proc =
                jdbc.queryForMap(
                        "SELECT p.prosecdef, p.proconfig::text AS config,"
                                + " pg_get_userbyid(p.proowner) AS owner,"
                                + " format_type(p.prorettype, NULL) AS returns"
                                + " FROM pg_proc p"
                                + " WHERE p.oid = 'peoplehub_current_organization_id()'::regprocedure");
        assertThat(proc)
                .containsEntry("prosecdef", false)
                .containsEntry("config", "{\"search_path=pg_catalog, pg_temp\"}")
                .containsEntry("owner", TestDatabaseRoles.OWNER_ROLE)
                .containsEntry("returns", "uuid");
        List<String> grantees =
                jdbc.queryForList(
                        "SELECT CASE WHEN a.grantee = 0 THEN 'PUBLIC'"
                                + " ELSE pg_get_userbyid(a.grantee) END"
                                + " FROM pg_proc p, aclexplode(p.proacl) a"
                                + " WHERE p.oid = 'peoplehub_current_organization_id()'::regprocedure"
                                + " AND a.privilege_type = 'EXECUTE' AND a.grantee <> p.proowner",
                        String.class);
        assertThat(grantees).containsExactly(RUNTIME);
    }

    @Test
    void theHelperAnswersTheBoundTenant() throws SQLException {
        UUID org = UUID.randomUUID();
        try (Connection runtime = TestDatabaseRoles.runtimeConnection(postgres)) {
            runtime.setAutoCommit(false);
            try (PreparedStatement bind =
                            runtime.prepareStatement(
                                    "SELECT set_config('peoplehub.organization_id', ?, true)");
                    Statement s = runtime.createStatement()) {
                bind.setString(1, org.toString());
                bind.executeQuery().close();
                try (ResultSet rs = s.executeQuery("SELECT peoplehub_current_organization_id()")) {
                    rs.next();
                    assertThat(rs.getObject(1, UUID.class)).isEqualTo(org);
                }
            } finally {
                runtime.rollback();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unset", "empty-after-transaction", "empty", "not-a-uuid", "12345"})
    void theHelperFailsLoudlyWithoutAValidTenant(String state) throws SQLException {
        try (Connection runtime = TestDatabaseRoles.runtimeConnection(postgres)) {
            putInState(runtime, state);
            assertThatThrownBy(
                            () -> {
                                try (Statement s = runtime.createStatement()) {
                                    s.executeQuery("SELECT peoplehub_current_organization_id()")
                                            .close();
                                }
                            })
                    .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(NO_TENANT));
        }
    }

    // ---- missing tenant: the runtime role's own connection, with its production grants ----

    @ParameterizedTest
    @MethodSource("tenantTables")
    void readingAnyTenantTableWithoutATenantFails(String table) throws SQLException {
        seedBoth(table);
        for (String state : List.of("unset", "empty-after-transaction", "not-a-uuid")) {
            try (Connection runtime = TestDatabaseRoles.runtimeConnection(postgres)) {
                putInState(runtime, state);
                assertThatThrownBy(
                                () -> {
                                    try (Statement s = runtime.createStatement()) {
                                        s.executeQuery("SELECT count(*) FROM " + table).close();
                                    }
                                })
                        .as(table + " read with the tenant " + state)
                        .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(NO_TENANT));
            }
        }
    }

    @Test
    void writingWithoutATenantFails() throws SQLException {
        Seed seed = seedBoth("employee");
        try (Connection runtime = TestDatabaseRoles.runtimeConnection(postgres)) {
            // INSERT, on columns the runtime role is granted.
            assertThatThrownBy(
                            () -> {
                                try (PreparedStatement ps =
                                        runtime.prepareStatement(
                                                "INSERT INTO email_outbox (organization_id,"
                                                        + " recipient, type) VALUES (?, 'x@x.io',"
                                                        + " 'EMPLOYEE_INVITED')")) {
                                    ps.setObject(1, seed.a());
                                    ps.executeUpdate();
                                }
                            })
                    .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(NO_TENANT));
            // UPDATE, on a column the runtime role is granted.
            assertThatThrownBy(
                            () -> {
                                try (PreparedStatement ps =
                                        runtime.prepareStatement(
                                                "UPDATE employee SET name = 'x' WHERE id = ?")) {
                                    ps.setObject(1, UUID.fromString(seed.aRow()));
                                    ps.executeUpdate();
                                }
                            })
                    .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(NO_TENANT));
        }
        // DELETE: the runtime role holds DELETE on no tenant table, so grant it for one rolled-back
        // transaction to see the policy stop it without a tenant too.
        assertThatThrownBy(
                        () ->
                                asRuntimeRolledBack(
                                        "employee",
                                        null,
                                        c ->
                                                update(
                                                        c,
                                                        "DELETE FROM employee WHERE id = ?::uuid",
                                                        seed.aRow())))
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(NO_TENANT));
    }

    // ---- isolation, for each of the 13 tables ----

    @ParameterizedTest
    @MethodSource("tenantTables")
    void eachTenantReadsOnlyItsOwnRows(String table) {
        Seed seed = seedBoth(table);
        String locator = locatorOf(table);
        String key = keyOf(table);

        for (UUID[] view : new UUID[][] {{seed.a(), seed.b()}, {seed.b(), seed.a()}}) {
            UUID own = view[0];
            UUID other = view[1];
            String ownRow = own.equals(seed.a()) ? seed.aRow() : seed.bRow();
            String otherRow = own.equals(seed.a()) ? seed.bRow() : seed.aRow();
            asRuntimeRolledBack(
                    table,
                    own,
                    c -> {
                        assertThat(
                                        count(
                                                c,
                                                "SELECT count(*) FROM "
                                                        + table
                                                        + " WHERE "
                                                        + locator
                                                        + "::text = ?",
                                                ownRow))
                                .as(table + ": its own row")
                                .isEqualTo(1);
                        assertThat(
                                        count(
                                                c,
                                                "SELECT count(*) FROM "
                                                        + table
                                                        + " WHERE "
                                                        + locator
                                                        + "::text = ?",
                                                otherRow))
                                .as(table + ": the other organization's row, by its known id")
                                .isZero();
                        assertThat(
                                        count(
                                                c,
                                                "SELECT count(*) FROM "
                                                        + table
                                                        + " WHERE "
                                                        + key
                                                        + " = ?::uuid",
                                                other.toString()))
                                .as(table + ": anything of the other organization")
                                .isZero();
                        assertThat(
                                        count(
                                                c,
                                                "SELECT count(*) FROM "
                                                        + table
                                                        + " WHERE "
                                                        + key
                                                        + " <> ?::uuid",
                                                own.toString()))
                                .as(table + ": anything not its own")
                                .isZero();
                        return null;
                    });
        }
    }

    @ParameterizedTest
    @MethodSource("tenantTables")
    void aTenantCannotUpdateOrDeleteAnotherTenantsRows(String table) {
        Seed seed = seedBoth(table);
        String locator = locatorOf(table);
        String key = keyOf(table);
        if (table.equals("audit_log")) {
            // Append-only (V3): its trigger refuses every UPDATE and DELETE, of any row, for
            // everyone,
            // before row-level security is even consulted. Stronger than the policy.
            for (String sql :
                    List.of(
                            "UPDATE audit_log SET action = action WHERE id::text = ?",
                            "DELETE FROM audit_log WHERE id::text = ?")) {
                for (String row : List.of(seed.aRow(), seed.bRow())) {
                    assertThatThrownBy(
                                    () ->
                                            asRuntimeRolledBack(
                                                    table, seed.a(), c -> update(c, sql, row)))
                            .as(sql)
                            .satisfies(
                                    e ->
                                            assertThat(SqlErrors.sqlMessage(e))
                                                    .contains("append-only"));
                }
            }
            return;
        }

        int updated =
                asRuntimeRolledBack(
                        table,
                        seed.a(),
                        c ->
                                update(
                                        c,
                                        "UPDATE "
                                                + table
                                                + " SET "
                                                + key
                                                + " = "
                                                + key
                                                + " WHERE "
                                                + locator
                                                + "::text = ?",
                                        seed.bRow()));
        int deleted =
                asRuntimeRolledBack(
                        table,
                        seed.a(),
                        c ->
                                update(
                                        c,
                                        "DELETE FROM " + table + " WHERE " + locator + "::text = ?",
                                        seed.bRow()));
        int ownUpdated =
                asRuntimeRolledBack(
                        table,
                        seed.a(),
                        c ->
                                update(
                                        c,
                                        "UPDATE "
                                                + table
                                                + " SET "
                                                + key
                                                + " = "
                                                + key
                                                + " WHERE "
                                                + locator
                                                + "::text = ?",
                                        seed.aRow()));

        assertThat(updated).as(table + ": another tenant's row is not there to update").isZero();
        assertThat(deleted).as(table + ": another tenant's row is not there to delete").isZero();
        assertThat(ownUpdated).as(table + ": its own row is").isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM "
                                        + table
                                        + " WHERE "
                                        + locator
                                        + "::text = ?",
                                Long.class,
                                seed.bRow()))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("tenantTables")
    void aTenantCannotInsertARowForAnotherTenant(String table) {
        Seed seed = seedBoth(table);

        assertThatThrownBy(
                        () ->
                                asRuntimeRolledBack(
                                        table,
                                        seed.a(),
                                        c -> insertRow(c, table, seed.b(), seed.bEmployee())))
                .as(table)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(RLS_VIOLATION))
                .satisfies(e -> assertThat(SqlErrors.sqlMessage(e)).contains("row-level security"));
        // The same statement for its own organization is allowed (organization excepted: a new
        // organization always has a new id, so it can never be the bound tenant).
        if (!table.equals("organization")) {
            asRuntimeRolledBack(
                    table, seed.a(), c -> insertRow(c, table, seed.a(), seed.aEmployee()));
        }
    }

    @ParameterizedTest
    @MethodSource("tenantTables")
    void aTenantCannotMoveItsRowToAnotherTenant(String table) {
        Seed seed = seedBoth(table);
        String locator = locatorOf(table);

        assertThatThrownBy(
                        () ->
                                asRuntimeRolledBack(
                                        table,
                                        seed.a(),
                                        c ->
                                                update(
                                                        c,
                                                        "UPDATE "
                                                                + table
                                                                + " SET "
                                                                + keyOf(table)
                                                                + " = ?::uuid WHERE "
                                                                + locator
                                                                + "::text = ?",
                                                        seed.b().toString(),
                                                        seed.aRow())))
                .as(table)
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        // audit_log: its append-only trigger refuses first (V3).
                                        .containsAnyOf("row-level security", "append-only"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT "
                                        + keyOf(table)
                                        + " FROM "
                                        + table
                                        + " WHERE "
                                        + locator
                                        + "::text = ?",
                                UUID.class,
                                seed.aRow()))
                .isEqualTo(seed.a());
    }

    // ---- helpers ----

    private record Seed(UUID a, UUID b, UUID aEmployee, UUID bEmployee, String aRow, String bRow) {}

    /** Two organizations, each with an employee and one row of {@code table}. */
    private Seed seedBoth(String table) {
        UUID a = newOrganization();
        UUID b = newOrganization();
        UUID aEmployee = newEmployee(a);
        UUID bEmployee = newEmployee(b);
        String aRow = table.equals("organization") ? a.toString() : fixtureRow(table, a, aEmployee);
        String bRow = table.equals("organization") ? b.toString() : fixtureRow(table, b, bEmployee);
        return new Seed(a, b, aEmployee, bEmployee, aRow, bRow);
    }

    private UUID newOrganization() {
        return UUID.fromString(fixtureRow("organization", null, null));
    }

    private UUID newEmployee(UUID org) {
        return UUID.fromString(fixtureRow("employee", org, null));
    }

    private String fixtureRow(String table, UUID org, UUID employee) {
        return jdbc.execute((ConnectionCallback<String>) c -> insertRow(c, table, org, employee));
    }

    private static String insertRow(Connection c, String table, UUID org, UUID employee)
            throws SQLException {
        String sql = INSERT_ROW.get(table);
        String u = UUID.randomUUID().toString();
        List<Object> params = new java.util.ArrayList<>();
        StringBuilder jdbcSql = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(":(org|emp|u)").matcher(sql);
        while (m.find()) {
            m.appendReplacement(jdbcSql, "?");
            switch (m.group(1)) {
                case "org" -> params.add(org);
                case "emp" -> params.add(employee);
                default -> params.add(u);
            }
        }
        m.appendTail(jdbcSql);
        String finalSql =
                jdbcSql.toString().replace("? ||", "?::text ||").replace("|| ?", "|| ?::text");
        try (PreparedStatement ps = c.prepareStatement(finalSql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /**
     * Runs {@code work} as the runtime role with {@code tenant} bound (none when null), inside one
     * superuser transaction that grants {@code table} to the runtime role first and is always
     * rolled back.
     */
    private <T> T asRuntimeRolledBack(String table, UUID tenant, SqlWork<T> work) {
        return jdbc.execute(
                (ConnectionCallback<T>)
                        c -> {
                            c.setAutoCommit(false);
                            try (Statement s = c.createStatement()) {
                                s.execute("GRANT ALL ON public." + table + " TO " + RUNTIME);
                                s.execute("SET LOCAL ROLE " + RUNTIME);
                                if (tenant != null) {
                                    s.executeQuery(
                                                    "SELECT set_config('peoplehub.organization_id', '"
                                                            + tenant
                                                            + "', true)")
                                            .close();
                                }
                                return work.run(c);
                            } finally {
                                c.rollback();
                                c.setAutoCommit(true);
                            }
                        });
    }

    /** Puts a runtime-role connection's tenant setting into one of the missing states. */
    private static void putInState(Connection runtime, String state) throws SQLException {
        try (Statement s = runtime.createStatement()) {
            switch (state) {
                case "unset" -> {
                    // A fresh connection has never had the setting.
                }
                case "empty-after-transaction" -> {
                    // What PostgreSQL reports once a transaction-local value has ended: ''.
                    runtime.setAutoCommit(false);
                    s.executeQuery(
                                    "SELECT set_config('peoplehub.organization_id', '"
                                            + UUID.randomUUID()
                                            + "', true)")
                            .close();
                    runtime.commit();
                    runtime.setAutoCommit(true);
                }
                case "empty" ->
                        s.executeQuery("SELECT set_config('peoplehub.organization_id', '', false)")
                                .close();
                default ->
                        s.executeQuery(
                                        "SELECT set_config('peoplehub.organization_id', '"
                                                + state
                                                + "', false)")
                                .close();
            }
        }
    }

    private static long count(Connection c, String sql, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static int update(Connection c, String sql, String... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setString(i + 1, values[i]);
            }
            return ps.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }
}
