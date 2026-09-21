package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestDatabaseRoles;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The exact privileges of the runtime role after all migrations (B0-6/2, B0-6/4, B0-6/9).
 *
 * <p>This is a fail-closed inventory: it lists every table in the {@code public} schema. A new
 * table or a widened grant fails here until this test is changed on purpose, so the runtime role
 * can never quietly gain UPDATE or DELETE on something (least privilege, Spec 15).
 */
@IntegrationTest
class RuntimePrivilegesTest {

    private static final String ROLE = TestDatabaseRoles.RUNTIME_ROLE;
    private static final List<String> TABLE_PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    /** Table-level privileges the runtime role must have, by table. Absent means none. */
    private static final Map<String, Set<String>> EXPECTED_TABLE_LEVEL =
            Map.of(
                    "audit_log", Set.of("SELECT"),
                    "shedlock", Set.of("SELECT", "INSERT", "UPDATE"),
                    "flyway_schema_history", Set.of());

    private static final Set<String> AUDIT_INSERT_COLUMNS =
            Set.of(
                    "organization_id",
                    "actor_id",
                    "action",
                    "target_type",
                    "target_id",
                    "ip",
                    "correlation_id",
                    "details");

    @Autowired private JdbcTemplate jdbc;

    @Test
    void theInventoryOfTablesIsExactlyWhatThisTestKnowsAbout() {
        List<String> tables =
                jdbc.queryForList(
                        "SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
                        String.class);

        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLE_LEVEL.keySet());
    }

    @Test
    void tableLevelPrivilegesAreExactlyTheExpectedOnes() {
        Map<String, Set<String>> actual = new TreeMap<>();
        for (String table : EXPECTED_TABLE_LEVEL.keySet()) {
            Set<String> granted = new TreeSet<>();
            for (String privilege : TABLE_PRIVILEGES) {
                Boolean has =
                        jdbc.queryForObject(
                                "SELECT has_table_privilege(?, ?, ?)",
                                Boolean.class,
                                ROLE,
                                "public." + table,
                                privilege);
                if (Boolean.TRUE.equals(has)) {
                    granted.add(privilege);
                }
            }
            actual.put(table, granted);
        }

        assertThat(actual).isEqualTo(new TreeMap<>(EXPECTED_TABLE_LEVEL));
    }

    @Test
    void auditLogInsertIsGrantedOnExactlyTheEightWriterColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'audit_log'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.audit_log', ?, 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on audit_log." + column)
                    .isEqualTo(AUDIT_INSERT_COLUMNS.contains(column));
        }
        // The two columns the database generates are the ones it may not supply.
        assertThat(AUDIT_INSERT_COLUMNS).doesNotContain("id", "occurred_at");
    }

    @Test
    void auditLogHasNoUpdateOrReferencesPrivilegeOnAnyColumn() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'audit_log'",
                        String.class)) {
            for (String privilege : List.of("UPDATE", "REFERENCES")) {
                Boolean has =
                        jdbc.queryForObject(
                                "SELECT has_column_privilege(?, 'public.audit_log', ?, ?)",
                                Boolean.class,
                                ROLE,
                                column,
                                privilege);
                assertThat(has).as(privilege + " on audit_log." + column).isFalse();
            }
        }
    }

    @Test
    void auditLogGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.audit_log'::regclass",
                        String.class);

        // An ACL entry with no grantee before "=" is a grant to PUBLIC.
        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void shedLockNeverNeedsDelete() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT has_table_privilege(?, 'public.shedlock', 'DELETE')",
                                Boolean.class,
                                ROLE))
                .isFalse();
        // ShedLockRuntimeRoleTest proves lock, unlock, re-lock and takeover all work without it.
    }

    @Test
    void theRoleHasNoSequenceSchemaOrDatabaseCreationPrivileges() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT has_sequence_privilege(?, 'public.audit_log_id_seq', 'USAGE')"
                                        + " OR has_sequence_privilege(?, 'public.audit_log_id_seq',"
                                        + " 'UPDATE')",
                                Boolean.class,
                                ROLE,
                                ROLE))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT has_schema_privilege(?, 'public', 'CREATE')",
                                Boolean.class,
                                ROLE))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT has_schema_privilege(?, 'public', 'USAGE')",
                                Boolean.class,
                                ROLE))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT has_database_privilege(?, current_database(), 'CREATE')",
                                Boolean.class,
                                ROLE))
                .isFalse();
    }

    @Test
    void theRuntimeRoleOwnsNothingAndHasNoSpecialAttributes() {
        Integer owned =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_tables WHERE tableowner = ?", Integer.class, ROLE);
        Map<String, Object> role =
                jdbc.queryForMap(
                        "SELECT rolsuper, rolcreaterole, rolcreatedb, rolbypassrls, rolreplication"
                                + " FROM pg_roles WHERE rolname = ?",
                        ROLE);

        assertThat(owned).isZero();
        assertThat(role.values()).containsOnly(false);
    }
}
