package com.peoplehub.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts a throwaway, real {@code organization} row for tests that need a valid tenant FK target
 * (b2-1, V12: {@code audit_log}/{@code email_outbox}/{@code notification}/{@code
 * notification_preference} all gained a real foreign key to {@code organization(id)}). Before V12,
 * these tests could use any non-nil {@code UUID.randomUUID()} as a synthetic organization id; now
 * it must resolve to a real row. Each call inserts a fresh row with a unique login key, so tests
 * never collide with each other and need no special cleanup of their own beyond what they already
 * do for their own tables.
 */
public final class TestOrganizations {

    private TestOrganizations() {}

    /** Inserts one throwaway organization and returns its database-generated id. */
    public static UUID insert(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "INSERT INTO organization (name, login_key_normalized, timezone) VALUES (?, ?, ?)"
                        + " RETURNING id",
                UUID.class,
                "Test Org",
                "test-org-" + UUID.randomUUID(),
                "Asia/Kolkata");
    }
}
