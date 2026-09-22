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
                    // has_table_privilege only reports UPDATE as a table-level privilege when it is
                    // granted on every column; V5 grants it on 6 of 12, so it correctly does not
                    // show
                    // up here even though emailOutboxUpdateIsGrantedOnExactlyTheSixProcessorColumns
                    // proves the column-level grant exists (same reasoning already applies to why
                    // INSERT never appeared here either, above).
                    "email_outbox", Set.of("SELECT"),
                    // Same reasoning as email_outbox: SELECT is table-level (all columns), but
                    // UPDATE is column-level on a strict subset (notification: just "read";
                    // notification_preference: just "email"/"in_app"), so it correctly does not
                    // appear as a table-level privilege here either.
                    "notification", Set.of("SELECT"),
                    "notification_preference", Set.of("SELECT"),
                    // b1-4 (V7): SELECT is table-level, INSERT is column-level on (email, reason)
                    // only (not "since", database-generated), same reasoning as every other table
                    // above.
                    "email_suppression", Set.of("SELECT"),
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

    /**
     * b1-1 grants INSERT on exactly these email_outbox columns. Not id or created_at
     * (database-generated), and not status/attempts/next_attempt_at/provider_message_id/error/
     * last_attempt_at either: nothing in b1-1 writes them, so UPDATE and the rest of INSERT wait
     * for the b1-2 migration that actually needs them (V4's own comments).
     */
    private static final Set<String> EMAIL_OUTBOX_INSERT_COLUMNS =
            Set.of("organization_id", "recipient", "type", "payload");

    /**
     * b1-2 (V5) grants UPDATE on exactly these six columns -- what {@code EmailOutboxProcessor}
     * writes. Not organization_id, recipient, type or payload (immutable after creation), and not
     * id or created_at (database-generated).
     */
    private static final Set<String> EMAIL_OUTBOX_UPDATE_COLUMNS =
            Set.of(
                    "status",
                    "attempts",
                    "next_attempt_at",
                    "provider_message_id",
                    "error",
                    "last_attempt_at");

    /** b1-3 (V6) grants INSERT on exactly these notification columns. Not id or created_at. */
    private static final Set<String> NOTIFICATION_INSERT_COLUMNS =
            Set.of("organization_id", "employee_id", "type", "payload");

    /** b1-3 (V6) grants UPDATE on exactly this one notification column: mark-read. */
    private static final Set<String> NOTIFICATION_UPDATE_COLUMNS = Set.of("read");

    /** b1-3 (V6) grants INSERT on all five notification_preference columns (its whole identity). */
    private static final Set<String> NOTIFICATION_PREFERENCE_INSERT_COLUMNS =
            Set.of("organization_id", "employee_id", "type", "email", "in_app");

    /** b1-3 (V6) grants UPDATE on exactly these; the other three are the primary key. */
    private static final Set<String> NOTIFICATION_PREFERENCE_UPDATE_COLUMNS =
            Set.of("email", "in_app");

    /** b1-4 (V7) grants INSERT on exactly these email_suppression columns. Not "since". */
    private static final Set<String> EMAIL_SUPPRESSION_INSERT_COLUMNS = Set.of("email", "reason");

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
    void emailOutboxInsertIsGrantedOnExactlyTheFourWriterColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'email_outbox'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.email_outbox', ?, 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on email_outbox." + column)
                    .isEqualTo(EMAIL_OUTBOX_INSERT_COLUMNS.contains(column));
        }
        // The two columns the database generates are the ones it may not supply, same as audit_log.
        assertThat(EMAIL_OUTBOX_INSERT_COLUMNS).doesNotContain("id", "created_at");
    }

    @Test
    void emailOutboxUpdateIsGrantedOnExactlyTheSixProcessorColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'email_outbox'",
                        String.class)) {
            Boolean canUpdate =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.email_outbox', ?, 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canUpdate)
                    .as("UPDATE on email_outbox." + column)
                    .isEqualTo(EMAIL_OUTBOX_UPDATE_COLUMNS.contains(column));
        }
    }

    @Test
    void emailOutboxHasNoReferencesPrivilegeOnAnyColumn() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'email_outbox'",
                        String.class)) {
            Boolean has =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.email_outbox', ?, 'REFERENCES')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(has).as("REFERENCES on email_outbox." + column).isFalse();
        }
    }

    @Test
    void emailOutboxGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.email_outbox'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void notificationInsertIsGrantedOnExactlyTheFourWriterColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'notification'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.notification', ?, 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on notification." + column)
                    .isEqualTo(NOTIFICATION_INSERT_COLUMNS.contains(column));
        }
        assertThat(NOTIFICATION_INSERT_COLUMNS).doesNotContain("id", "created_at");
    }

    @Test
    void notificationUpdateIsGrantedOnExactlyTheReadColumn() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'notification'",
                        String.class)) {
            Boolean canUpdate =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.notification', ?, 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canUpdate)
                    .as("UPDATE on notification." + column)
                    .isEqualTo(NOTIFICATION_UPDATE_COLUMNS.contains(column));
        }
    }

    @Test
    void notificationGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.notification'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void notificationPreferenceInsertIsGrantedOnAllFiveColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'notification_preference'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.notification_preference', ?,"
                                    + " 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on notification_preference." + column)
                    .isEqualTo(NOTIFICATION_PREFERENCE_INSERT_COLUMNS.contains(column));
        }
    }

    @Test
    void notificationPreferenceUpdateIsGrantedOnlyOnEmailAndInApp() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'notification_preference'",
                        String.class)) {
            Boolean canUpdate =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.notification_preference', ?,"
                                    + " 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canUpdate)
                    .as("UPDATE on notification_preference." + column)
                    .isEqualTo(NOTIFICATION_PREFERENCE_UPDATE_COLUMNS.contains(column));
        }
    }

    @Test
    void notificationPreferenceGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.notification_preference'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void emailSuppressionInsertIsGrantedOnExactlyEmailAndReason() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'email_suppression'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.email_suppression', ?, 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on email_suppression." + column)
                    .isEqualTo(EMAIL_SUPPRESSION_INSERT_COLUMNS.contains(column));
        }
        assertThat(EMAIL_SUPPRESSION_INSERT_COLUMNS).doesNotContain("since");
    }

    @Test
    void emailSuppressionHasNoUpdateOrReferencesPrivilegeOnAnyColumn() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'email_suppression'",
                        String.class)) {
            for (String privilege : List.of("UPDATE", "REFERENCES")) {
                Boolean has =
                        jdbc.queryForObject(
                                "SELECT has_column_privilege(?, 'public.email_suppression', ?, ?)",
                                Boolean.class,
                                ROLE,
                                column,
                                privilege);
                assertThat(has).as(privilege + " on email_suppression." + column).isFalse();
            }
        }
    }

    @Test
    void emailSuppressionGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.email_suppression'::regclass",
                        String.class);

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
