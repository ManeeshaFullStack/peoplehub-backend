package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.SqlErrors;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V18 (b2-6, B2-6/6): the refresh-token revoke reasons {@code SESSION_REVOKED} and {@code
 * DEACTIVATED}, added to V16's list. Runs as the container's superuser; {@code
 * SessionRevokeReasonsRuntimeRoleTest} covers the runtime role. Every test creates its own rows.
 */
@IntegrationTest
class SessionRevokeReasonsMigrationTest {

    private static final List<String> REASONS =
            List.of(
                    "ROTATED",
                    "LOGOUT",
                    "REUSE_DETECTED",
                    "PASSWORD_RESET",
                    "PASSWORD_CHANGED",
                    "SESSION_REVOKED",
                    "DEACTIVATED");

    @Autowired private JdbcTemplate jdbc;

    private UUID insertToken() {
        UUID org =
                jdbc.queryForObject(
                        "INSERT INTO organization (name, login_key_normalized, timezone)"
                                + " VALUES ('Acme Corp', ?, 'Asia/Kolkata') RETURNING id",
                        UUID.class,
                        "org-" + UUID.randomUUID());
        String email = "jane-" + UUID.randomUUID() + "@example.com";
        UUID employee =
                jdbc.queryForObject(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, role) VALUES (?, ?, 'Jane Doe', ?, ?,"
                                + " 'EMPLOYEE') RETURNING id",
                        UUID.class,
                        org,
                        "E-" + UUID.randomUUID(),
                        email,
                        email);
        return jdbc.queryForObject(
                "INSERT INTO refresh_token (organization_id, employee_id, token_hash, family_id,"
                        + " expires_at, absolute_expires_at) VALUES (?, ?, ?, gen_random_uuid(),"
                        + " now() + interval '30 days', now() + interval '90 days') RETURNING id",
                UUID.class,
                org,
                employee,
                "hash-" + UUID.randomUUID());
    }

    private int revoke(UUID token, String reason) {
        return jdbc.update(
                "UPDATE refresh_token SET revoked = true, revoked_at = now(), revoke_reason = ?"
                        + " WHERE id = ?",
                reason,
                token);
    }

    @Test
    void v18AppliesCleanly() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE version = '18'"
                                        + " AND success",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theCheckStillListsTheSevenReasons() {
        String definition =
                jdbc.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conname = 'ck_refresh_token_revoke_reason'",
                        String.class);

        List<String> values =
                Pattern.compile("'([^']*)'")
                        .matcher(definition)
                        .results()
                        .map(match -> match.group(1))
                        .toList();
        // V19 (b2-7) extends the list; the exact list is asserted by MfaPolicyMigrationTest.
        assertThat(values).containsAll(REASONS);
    }

    @Test
    void everyKnownReasonIsAcceptedIncludingTheTwoNewOnes() {
        for (String reason : REASONS) {
            assertThat(revoke(insertToken(), reason)).as(reason).isEqualTo(1);
        }
    }

    @Test
    void anUnknownReasonIsRejected() {
        UUID token = insertToken();

        for (String reason : List.of("EXPIRED", "session_revoked", "DEACTIVATE", "")) {
            assertThatThrownBy(() -> revoke(token, reason))
                    .as(reason)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(
                            e ->
                                    assertThat(SqlErrors.sqlState(e))
                                            .isEqualTo(SqlErrors.CHECK_VIOLATION))
                    .message()
                    .contains("ck_refresh_token_revoke_reason");
        }
    }

    @Test
    void theReasonColumnIsDocumented() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT col_description('refresh_token'::regclass, ordinal_position)"
                                        + " FROM information_schema.columns"
                                        + " WHERE table_name = 'refresh_token'"
                                        + " AND column_name = 'revoke_reason'",
                                String.class))
                .contains("SESSION_REVOKED")
                .contains("DEACTIVATED");
    }
}
