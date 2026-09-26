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

    /**
     * Table-level privileges the runtime role must have, by table. Absent means none.
     *
     * <p>{@code Map.ofEntries(...)} rather than {@code Map.of(...)}: b2-1 (V8-V11) pushed the table
     * count past {@code Map.of}'s ten-pair overload limit.
     */
    private static final Map<String, Set<String>> EXPECTED_TABLE_LEVEL =
            Map.ofEntries(
                    Map.entry("audit_log", Set.of("SELECT")),
                    Map.entry("shedlock", Set.of("SELECT", "INSERT", "UPDATE")),
                    // has_table_privilege only reports UPDATE as a table-level privilege when it is
                    // granted on every column; V5 grants it on 6 of 12, so it correctly does not
                    // show
                    // up here even though emailOutboxUpdateIsGrantedOnExactlyTheSixProcessorColumns
                    // proves the column-level grant exists (same reasoning already applies to why
                    // INSERT never appeared here either, above).
                    Map.entry("email_outbox", Set.of("SELECT")),
                    // Same reasoning as email_outbox: SELECT is table-level (all columns), but
                    // UPDATE is column-level on a strict subset (notification: just "read";
                    // notification_preference: just "email"/"in_app"), so it correctly does not
                    // appear as a table-level privilege here either.
                    Map.entry("notification", Set.of("SELECT")),
                    Map.entry("notification_preference", Set.of("SELECT")),
                    // b1-4 (V7): SELECT is table-level, INSERT is column-level on (email, reason)
                    // only (not "since", database-generated), same reasoning as every other table
                    // above.
                    Map.entry("email_suppression", Set.of("SELECT")),
                    // b2-1 (V8): SELECT is table-level; INSERT is column-level on (name,
                    // login_key_normalized, timezone), UPDATE on a different, narrower column set
                    // (name, timezone, status, onboarding_completed_at, updated_at) -- neither
                    // covers every column, so neither appears as a table-level privilege here.
                    Map.entry("organization", Set.of("SELECT")),
                    // b2-1 (V9): SELECT is table-level; INSERT and UPDATE are both column-level on
                    // strict subsets (UPDATE is split into four narrow grants across several use
                    // cases, none covering every column), same reasoning as every table above.
                    Map.entry("employee", Set.of("SELECT")),
                    // b2-1 (V10): SELECT is table-level; INSERT and UPDATE are both column-level on
                    // strict subsets, same reasoning as every table above.
                    Map.entry("employee_invitation", Set.of("SELECT")),
                    // b2-1 (V11): three tables, each SELECT table-level with INSERT/UPDATE on
                    // strict subsets. login_attempt gets no UPDATE grant at all (append-only, its
                    // own trigger + privilege boundary both enforce this, same defence-in-depth as
                    // audit_log).
                    Map.entry("refresh_token", Set.of("SELECT")),
                    Map.entry("login_attempt", Set.of("SELECT")),
                    Map.entry("mfa_recovery_code", Set.of("SELECT")),
                    // b2-2 (V13): SELECT is table-level; INSERT and UPDATE are both column-level on
                    // strict subsets, same reasoning as every table above.
                    Map.entry("organization_verification_token", Set.of("SELECT")),
                    // b2-5 (V17): SELECT is table-level; INSERT and UPDATE are both column-level on
                    // strict subsets, same reasoning as every table above.
                    Map.entry("password_reset_token", Set.of("SELECT")),
                    // b2-7 (V21, V22): SELECT is table-level; INSERT (and, for mfa_challenge,
                    // UPDATE)
                    // are column-level on strict subsets. session_step_up has no UPDATE at all.
                    Map.entry("mfa_challenge", Set.of("SELECT")),
                    Map.entry("session_step_up", Set.of("SELECT")),
                    Map.entry("flyway_schema_history", Set.of()));

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

    /** b2-1 (V8) grants INSERT on exactly these organization columns. Not id/status/timestamps. */
    private static final Set<String> ORGANIZATION_INSERT_COLUMNS =
            Set.of("name", "login_key_normalized", "timezone");

    /**
     * b2-1 (V8) grants UPDATE on exactly these organization columns. Not id/login_key_normalized.
     */
    private static final Set<String> ORGANIZATION_UPDATE_COLUMNS =
            Set.of(
                    "name",
                    "timezone",
                    "status",
                    "onboarding_completed_at",
                    "updated_at",
                    // b2-7 (V19): the organization MFA policy.
                    "mfa_policy");

    /**
     * b2-1 (V9) grants INSERT on exactly these employee columns. Not id/created_at/updated_at
     * (database-generated), not password_hash/mfa_enabled/mfa_totp_secret/default_approver_id/
     * exit_date/welcome_seen_at: no b2-1 code path writes them at insert time.
     */
    private static final Set<String> EMPLOYEE_INSERT_COLUMNS =
            Set.of(
                    "organization_id",
                    "department_id",
                    "employee_code",
                    "name",
                    "email",
                    "email_normalized",
                    "status",
                    "role",
                    "join_date");

    /**
     * b2-1 (V9) grants UPDATE on exactly these employee columns, across four narrow use-case
     * grants. employee_code is deliberately absent from every one (immutable by privilege, B0-6/12
     * discipline); email/email_normalized wait for the branch that actually changes them; role
     * arrived with b2-7 (V19).
     */
    private static final Set<String> EMPLOYEE_UPDATE_COLUMNS =
            Set.of(
                    "password_hash",
                    "mfa_enabled",
                    "mfa_totp_secret",
                    "status",
                    "exit_date",
                    "welcome_seen_at",
                    "department_id",
                    "name",
                    "default_approver_id",
                    "updated_at",
                    // b2-5 (V16): per-account lockout state.
                    "failed_login_count",
                    "locked_until",
                    // b2-7 (V19): promotion changes the role; MFA enrollment, selection, replay
                    // protection and reminder dismissal.
                    "role",
                    "mfa_required",
                    "mfa_enrolled_at",
                    "mfa_totp_last_step",
                    "mfa_reminder_dismissed_at",
                    // b2-7 (V23): the pending secret of a step-up re-enrollment.
                    "mfa_totp_pending_secret");

    /** b2-1 (V10) grants INSERT on exactly these employee_invitation columns. Not id/created_at. */
    private static final Set<String> EMPLOYEE_INVITATION_INSERT_COLUMNS =
            Set.of(
                    "organization_id",
                    "email_normalized",
                    "intended_role",
                    "token_hash",
                    "inviter_employee_id",
                    "expires_at");

    /** b2-1 (V10) grants UPDATE on exactly these employee_invitation columns. */
    private static final Set<String> EMPLOYEE_INVITATION_UPDATE_COLUMNS =
            Set.of("consumed_at", "revoked_at");

    /**
     * b2-1 (V11) and b2-3 (V14, organization_id) grant INSERT on exactly these refresh_token
     * columns. Not id/created_at, and not the revocation columns (a new token is never revoked).
     */
    private static final Set<String> REFRESH_TOKEN_INSERT_COLUMNS =
            Set.of(
                    "organization_id",
                    "employee_id",
                    "token_hash",
                    "family_id",
                    "expires_at",
                    "absolute_expires_at",
                    "device_label");

    /**
     * b2-1 (V11, revoked) and b2-3 (V14) grant UPDATE on exactly the revocation columns. Never the
     * token, its family, its expiries or its owner: rotation inserts a new row.
     */
    private static final Set<String> REFRESH_TOKEN_UPDATE_COLUMNS =
            Set.of("revoked", "revoked_at", "revoke_reason", "replaced_by_id");

    /**
     * b2-1 (V11) grants INSERT on exactly these login_attempt columns. Not id/occurred_at
     * (database-generated). No UPDATE columns at all: append-only, same as audit_log.
     */
    private static final Set<String> LOGIN_ATTEMPT_INSERT_COLUMNS =
            Set.of("organization_login_key_attempted", "email_attempted", "ip", "success");

    /**
     * b2-1 (V11) and b2-7 (V20, organization_id) grant INSERT on exactly these. Not id/created_at.
     */
    private static final Set<String> MFA_RECOVERY_CODE_INSERT_COLUMNS =
            Set.of("organization_id", "employee_id", "code_hash");

    /** b2-1 (V11, used_at) and b2-7 (V20, invalidated_at) grant UPDATE on exactly these. */
    private static final Set<String> MFA_RECOVERY_CODE_UPDATE_COLUMNS =
            Set.of("used_at", "invalidated_at");

    /**
     * b2-2 (V13) grants INSERT on exactly these organization_verification_token columns. Not
     * id/created_at (database-generated).
     */
    private static final Set<String> ORGANIZATION_VERIFICATION_TOKEN_INSERT_COLUMNS =
            Set.of("organization_id", "token_hash", "expires_at");

    /** b2-2 (V13) grants UPDATE on exactly this one organization_verification_token column. */
    private static final Set<String> ORGANIZATION_VERIFICATION_TOKEN_UPDATE_COLUMNS =
            Set.of("consumed_at");

    /**
     * b2-5 (V17) grants INSERT on exactly these password_reset_token columns. Not id/created_at
     * (database-generated) and not consumed_at/invalidated_at (a new token is never used).
     */
    private static final Set<String> PASSWORD_RESET_TOKEN_INSERT_COLUMNS =
            Set.of("organization_id", "employee_id", "token_hash", "expires_at");

    /** b2-5 (V17) grants UPDATE on exactly these two password_reset_token columns. */
    private static final Set<String> PASSWORD_RESET_TOKEN_UPDATE_COLUMNS =
            Set.of("consumed_at", "invalidated_at");

    /**
     * b2-7 (V21) grants INSERT on exactly these mfa_challenge columns. Not id/created_at
     * (database-generated) and not failed_attempts/consumed_at/invalidated_at (a new challenge is
     * unused).
     */
    private static final Set<String> MFA_CHALLENGE_INSERT_COLUMNS =
            Set.of(
                    "organization_id",
                    "employee_id",
                    "token_hash",
                    "purpose",
                    "device_label",
                    "ip",
                    "expires_at");

    /** b2-7 (V21) grants UPDATE on exactly the three state columns of mfa_challenge. */
    private static final Set<String> MFA_CHALLENGE_UPDATE_COLUMNS =
            Set.of("failed_attempts", "consumed_at", "invalidated_at");

    /**
     * b2-7 (V22) grants INSERT on exactly these session_step_up columns. Not id or verified_at: the
     * database, never the application, records when a step-up happened. No UPDATE at all.
     */
    private static final Set<String> SESSION_STEP_UP_INSERT_COLUMNS =
            Set.of("organization_id", "employee_id", "session_id", "method");

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
    void organizationInsertIsGrantedOnExactlyNameLoginKeyAndTimezone() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'organization'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.organization', ?, 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on organization." + column)
                    .isEqualTo(ORGANIZATION_INSERT_COLUMNS.contains(column));
        }
        assertThat(ORGANIZATION_INSERT_COLUMNS).doesNotContain("id", "status", "created_at");
    }

    @Test
    void organizationUpdateIsGrantedOnExactlyTheFiveMutableColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'organization'",
                        String.class)) {
            Boolean canUpdate =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.organization', ?, 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canUpdate)
                    .as("UPDATE on organization." + column)
                    .isEqualTo(ORGANIZATION_UPDATE_COLUMNS.contains(column));
        }
        assertThat(ORGANIZATION_UPDATE_COLUMNS).doesNotContain("id", "login_key_normalized");
    }

    @Test
    void organizationGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.organization'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void employeeInsertIsGrantedOnExactlyTheNineWriterColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'employee'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.employee', ?, 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on employee." + column)
                    .isEqualTo(EMPLOYEE_INSERT_COLUMNS.contains(column));
        }
        assertThat(EMPLOYEE_INSERT_COLUMNS).doesNotContain("id", "created_at", "updated_at");
    }

    @Test
    void employeeUpdateIsGrantedOnExactlyTheExpectedColumnsAndNeverEmployeeCode() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'employee'",
                        String.class)) {
            Boolean canUpdate =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.employee', ?, 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canUpdate)
                    .as("UPDATE on employee." + column)
                    .isEqualTo(EMPLOYEE_UPDATE_COLUMNS.contains(column));
        }
        assertThat(EMPLOYEE_UPDATE_COLUMNS)
                .doesNotContain("id", "employee_code", "organization_id", "created_at");
    }

    @Test
    void employeeGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.employee'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void employeeInvitationInsertIsGrantedOnExactlyTheSixWriterColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'employee_invitation'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.employee_invitation', ?,"
                                    + " 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on employee_invitation." + column)
                    .isEqualTo(EMPLOYEE_INVITATION_INSERT_COLUMNS.contains(column));
        }
        assertThat(EMPLOYEE_INVITATION_INSERT_COLUMNS).doesNotContain("id", "created_at");
    }

    @Test
    void employeeInvitationUpdateIsGrantedOnlyOnConsumedAtAndRevokedAt() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'employee_invitation'",
                        String.class)) {
            Boolean canUpdate =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.employee_invitation', ?,"
                                    + " 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canUpdate)
                    .as("UPDATE on employee_invitation." + column)
                    .isEqualTo(EMPLOYEE_INVITATION_UPDATE_COLUMNS.contains(column));
        }
    }

    @Test
    void employeeInvitationGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.employee_invitation'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void refreshTokenInsertIsGrantedOnExactlyTheSevenWriterColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'refresh_token'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.refresh_token', ?, 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on refresh_token." + column)
                    .isEqualTo(REFRESH_TOKEN_INSERT_COLUMNS.contains(column));
        }
        assertThat(REFRESH_TOKEN_INSERT_COLUMNS).doesNotContain("id", "created_at");
    }

    @Test
    void refreshTokenUpdateIsGrantedOnlyOnTheRevocationColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'refresh_token'",
                        String.class)) {
            Boolean canUpdate =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.refresh_token', ?, 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canUpdate)
                    .as("UPDATE on refresh_token." + column)
                    .isEqualTo(REFRESH_TOKEN_UPDATE_COLUMNS.contains(column));
        }
    }

    @Test
    void refreshTokenGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.refresh_token'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void loginAttemptInsertIsGrantedOnExactlyTheFourWriterColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'login_attempt'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.login_attempt', ?, 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on login_attempt." + column)
                    .isEqualTo(LOGIN_ATTEMPT_INSERT_COLUMNS.contains(column));
        }
        assertThat(LOGIN_ATTEMPT_INSERT_COLUMNS).doesNotContain("id", "occurred_at");
    }

    @Test
    void loginAttemptHasNoUpdatePrivilegeOnAnyColumn() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'login_attempt'",
                        String.class)) {
            Boolean has =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.login_attempt', ?, 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(has).as("UPDATE on login_attempt." + column).isFalse();
        }
    }

    @Test
    void loginAttemptGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.login_attempt'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void mfaRecoveryCodeInsertIsGrantedOnExactlyEmployeeIdAndCodeHash() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'mfa_recovery_code'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.mfa_recovery_code', ?,"
                                    + " 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on mfa_recovery_code." + column)
                    .isEqualTo(MFA_RECOVERY_CODE_INSERT_COLUMNS.contains(column));
        }
        assertThat(MFA_RECOVERY_CODE_INSERT_COLUMNS).doesNotContain("id", "created_at");
    }

    @Test
    void mfaRecoveryCodeUpdateIsGrantedOnlyOnUsedAtAndInvalidatedAt() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'mfa_recovery_code'",
                        String.class)) {
            Boolean canUpdate =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.mfa_recovery_code', ?,"
                                    + " 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canUpdate)
                    .as("UPDATE on mfa_recovery_code." + column)
                    .isEqualTo(MFA_RECOVERY_CODE_UPDATE_COLUMNS.contains(column));
        }
    }

    @Test
    void mfaRecoveryCodeGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.mfa_recovery_code'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void organizationVerificationTokenInsertIsGrantedOnExactlyTheThreeWriterColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'organization_verification_token'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?,"
                                    + " 'public.organization_verification_token', ?, 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on organization_verification_token." + column)
                    .isEqualTo(ORGANIZATION_VERIFICATION_TOKEN_INSERT_COLUMNS.contains(column));
        }
        assertThat(ORGANIZATION_VERIFICATION_TOKEN_INSERT_COLUMNS)
                .doesNotContain("id", "created_at");
    }

    @Test
    void organizationVerificationTokenUpdateIsGrantedOnlyOnConsumedAt() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'organization_verification_token'",
                        String.class)) {
            Boolean canUpdate =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?,"
                                    + " 'public.organization_verification_token', ?, 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canUpdate)
                    .as("UPDATE on organization_verification_token." + column)
                    .isEqualTo(ORGANIZATION_VERIFICATION_TOKEN_UPDATE_COLUMNS.contains(column));
        }
    }

    @Test
    void organizationVerificationTokenGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid ="
                                + " 'public.organization_verification_token'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void passwordResetTokenInsertIsGrantedOnExactlyTheFourWriterColumns() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'password_reset_token'",
                        String.class)) {
            Boolean canInsert =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.password_reset_token', ?,"
                                    + " 'INSERT')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canInsert)
                    .as("INSERT on password_reset_token." + column)
                    .isEqualTo(PASSWORD_RESET_TOKEN_INSERT_COLUMNS.contains(column));
        }
        assertThat(PASSWORD_RESET_TOKEN_INSERT_COLUMNS)
                .doesNotContain("id", "created_at", "consumed_at", "invalidated_at");
    }

    @Test
    void passwordResetTokenUpdateIsGrantedOnlyOnConsumedAtAndInvalidatedAt() {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'password_reset_token'",
                        String.class)) {
            Boolean canUpdate =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, 'public.password_reset_token', ?,"
                                    + " 'UPDATE')",
                            Boolean.class,
                            ROLE,
                            column);
            assertThat(canUpdate)
                    .as("UPDATE on password_reset_token." + column)
                    .isEqualTo(PASSWORD_RESET_TOKEN_UPDATE_COLUMNS.contains(column));
        }
    }

    @Test
    void passwordResetTokenGrantsNothingToPublic() {
        List<String> acl =
                jdbc.queryForList(
                        "SELECT unnest(relacl)::text FROM pg_class"
                                + " WHERE oid = 'public.password_reset_token'::regclass",
                        String.class);

        assertThat(acl).noneMatch(entry -> entry.startsWith("="));
    }

    @Test
    void mfaChallengeInsertAndUpdateAreGrantedOnExactlyTheExpectedColumns() {
        assertColumnPrivileges("mfa_challenge", "INSERT", MFA_CHALLENGE_INSERT_COLUMNS);
        assertColumnPrivileges("mfa_challenge", "UPDATE", MFA_CHALLENGE_UPDATE_COLUMNS);
        assertThat(MFA_CHALLENGE_INSERT_COLUMNS)
                .doesNotContain(
                        "id", "created_at", "failed_attempts", "consumed_at", "invalidated_at");
    }

    @Test
    void sessionStepUpIsInsertOnlyAndNeverChoosesItsTime() {
        assertColumnPrivileges("session_step_up", "INSERT", SESSION_STEP_UP_INSERT_COLUMNS);
        assertColumnPrivileges("session_step_up", "UPDATE", Set.of());
        assertThat(SESSION_STEP_UP_INSERT_COLUMNS).doesNotContain("id", "verified_at");
    }

    @Test
    void theB27TablesHaveNoReferencesPrivilegeAndGrantNothingToPublic() {
        for (String table : List.of("mfa_challenge", "session_step_up")) {
            assertColumnPrivileges(table, "REFERENCES", Set.of());
            List<String> acl =
                    jdbc.queryForList(
                            "SELECT unnest(relacl)::text FROM pg_class"
                                    + " WHERE oid = ('public.' || ?)::regclass",
                            String.class,
                            table);
            assertThat(acl).as(table).noneMatch(entry -> entry.startsWith("="));
        }
    }

    private void assertColumnPrivileges(String table, String privilege, Set<String> expected) {
        for (String column :
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                        String.class,
                        table)) {
            Boolean has =
                    jdbc.queryForObject(
                            "SELECT has_column_privilege(?, ('public.' || ?)::regclass, ?, ?)",
                            Boolean.class,
                            ROLE,
                            table,
                            column,
                            privilege);
            assertThat(has)
                    .as(privilege + " on " + table + "." + column)
                    .isEqualTo(expected.contains(column));
        }
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
