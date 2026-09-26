package com.peoplehub.support;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real tenant, employee and session rows for authentication tests (b2-3), written directly through
 * the privileged fixture connection ({@link PrivilegedFixture}). Every value that is unique in the
 * schema (login key, email, employee code, token hash) is freshly generated, so tests never collide
 * (B2 development rules).
 */
public final class TestIdentities {

    private TestIdentities() {}

    /** An {@code ACTIVE} organization with a fresh login key. */
    public static Organization activeOrganization(JdbcTemplate jdbc) {
        String loginKey = "org-" + UUID.randomUUID();
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO organization (name, login_key_normalized, timezone)"
                                + " VALUES (?, ?, 'Asia/Kolkata') RETURNING id",
                        UUID.class,
                        "Acme " + loginKey,
                        loginKey);
        jdbc.update("UPDATE organization SET status = 'ACTIVE' WHERE id = ?", id);
        return new Organization(id, loginKey);
    }

    /** An {@code ACTIVE} employee with a fresh email and the given password hash (may be null). */
    public static Employee activeEmployee(
            JdbcTemplate jdbc, Organization organization, String role, String passwordHash) {
        String email = "jane-" + UUID.randomUUID() + "@example.com";
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, status, role, join_date)"
                                + " VALUES (?, ?, 'Jane Doe', ?, ?, 'ACTIVE', ?, DATE '2026-01-05')"
                                + " RETURNING id",
                        UUID.class,
                        organization.id(),
                        "E-" + UUID.randomUUID(),
                        email,
                        email,
                        role);
        if (passwordHash != null) {
            jdbc.update("UPDATE employee SET password_hash = ? WHERE id = ?", passwordHash, id);
        }
        return new Employee(id, organization.id(), organization.loginKey(), email, role);
    }

    /**
     * A usable session (one unrevoked refresh token in a new family) for the employee, valid from
     * {@code now}; returns the family id to use as the access token's {@code sid}.
     */
    public static UUID activeSession(JdbcTemplate jdbc, Employee employee, Instant now) {
        UUID family = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO refresh_token (organization_id, employee_id, token_hash, family_id,"
                        + " expires_at, absolute_expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                employee.organizationId(),
                employee.id(),
                "test-" + UUID.randomUUID(),
                family,
                Timestamp.from(now.plus(Duration.ofDays(30))),
                Timestamp.from(now.plus(Duration.ofDays(90))));
        return family;
    }

    public record Organization(UUID id, String loginKey) {}

    public record Employee(
            UUID id, UUID organizationId, String organizationLoginKey, String email, String role) {}
}
