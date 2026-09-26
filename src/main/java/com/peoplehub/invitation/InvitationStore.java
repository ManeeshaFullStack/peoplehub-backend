package com.peoplehub.invitation;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Plain JDBC access to {@code employee}, {@code employee_invitation} and {@code organization} for
 * invitations (b2-4), the same no-entity style as {@code RegistrationService} and {@code
 * RefreshTokenStore}. Every lookup is qualified by the organization (Spec 15.1): there is no
 * unrestricted lookup by id. Only token hashes are stored or looked up.
 */
@Component
class InvitationStore {

    private static final String SELECT_ORGANIZATION =
            "SELECT name, login_key_normalized FROM organization WHERE id = ?";

    private static final String SELECT_EMPLOYEE_FOR_UPDATE =
            "SELECT id, name, email, status, role FROM employee"
                    + " WHERE organization_id = ? AND email_normalized = ? FOR UPDATE";

    private static final String INSERT_EMPLOYEE =
            "INSERT INTO employee (organization_id, employee_code, name, email, email_normalized,"
                    + " status, role, join_date) VALUES (?, ?, ?, ?, ?, 'INVITED', ?, ?)"
                    + " RETURNING id";

    private static final String EMPLOYEE_CODE_EXISTS =
            "SELECT EXISTS (SELECT 1 FROM employee WHERE organization_id = ?"
                    + " AND employee_code = ?)";

    private static final String HAS_OPEN_INVITATION =
            "SELECT EXISTS (SELECT 1 FROM employee_invitation WHERE organization_id = ?"
                    + " AND email_normalized = ? AND consumed_at IS NULL AND revoked_at IS NULL)";

    private static final String INSERT_INVITATION =
            "INSERT INTO employee_invitation (organization_id, email_normalized, intended_role,"
                    + " token_hash, inviter_employee_id, expires_at) VALUES (?, ?, ?, ?, ?, ?)"
                    + " RETURNING id";

    /**
     * An invitation by its token's hash, with its organization and invited employee (same
     * organization, same email). The tenant comes from the invitation row itself, never from the
     * caller.
     */
    private static final String SELECT_BY_TOKEN_HASH =
            "SELECT i.id, i.organization_id, i.intended_role, i.expires_at, i.consumed_at,"
                    + " i.revoked_at, o.name AS organization_name, o.status AS organization_status,"
                    + " e.id AS employee_id, e.name AS employee_name, e.email AS employee_email,"
                    + " e.status AS employee_status, e.role AS employee_role"
                    + " FROM employee_invitation i"
                    + " JOIN organization o ON o.id = i.organization_id"
                    + " JOIN employee e ON e.organization_id = i.organization_id"
                    + " AND e.email_normalized = i.email_normalized"
                    + " WHERE i.organization_id = ? AND i.token_hash = ?";

    private static final String CONSUME_INVITATION =
            "UPDATE employee_invitation SET consumed_at = ?"
                    + " WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL";

    private static final String ACTIVATE_EMPLOYEE =
            "UPDATE employee SET password_hash = ?, status = 'ACTIVE', updated_at = ?"
                    + " WHERE id = ? AND organization_id = ? AND status = 'INVITED'";

    /**
     * An invitation by id <em>within one organization</em>, with its invited employee, locked until
     * the transaction ends. Another organization's invitation id finds nothing (Spec 15.1, D22).
     */
    private static final String SELECT_BY_ID_FOR_UPDATE =
            "SELECT i.id, i.email_normalized, i.intended_role, i.consumed_at, i.revoked_at,"
                    + " e.id AS employee_id, e.name AS employee_name, e.email AS employee_email,"
                    + " e.status AS employee_status"
                    + " FROM employee_invitation i"
                    + " JOIN employee e ON e.organization_id = i.organization_id"
                    + " AND e.email_normalized = i.email_normalized"
                    + " WHERE i.id = ? AND i.organization_id = ? FOR UPDATE OF i";

    private static final String REVOKE_INVITATION =
            "UPDATE employee_invitation SET revoked_at = ?"
                    + " WHERE id = ? AND organization_id = ?"
                    + " AND consumed_at IS NULL AND revoked_at IS NULL";

    private static final String REVOKE_OPEN_FOR_EMAIL =
            "UPDATE employee_invitation SET revoked_at = ?"
                    + " WHERE organization_id = ? AND email_normalized = ?"
                    + " AND consumed_at IS NULL AND revoked_at IS NULL";

    private final JdbcClient jdbc;

    InvitationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Organization organization(UUID organizationId) {
        return jdbc.sql(SELECT_ORGANIZATION)
                .param(organizationId)
                .query(
                        (rs, rowNum) ->
                                new Organization(
                                        rs.getString("name"), rs.getString("login_key_normalized")))
                .single();
    }

    /** The employee with this email in the organization, row-locked until the transaction ends. */
    Optional<Employee> employeeForUpdate(UUID organizationId, String emailNormalized) {
        return jdbc.sql(SELECT_EMPLOYEE_FOR_UPDATE)
                .param(organizationId)
                .param(emailNormalized)
                .query(
                        (rs, rowNum) ->
                                new Employee(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("name"),
                                        rs.getString("email"),
                                        rs.getString("status"),
                                        rs.getString("role")))
                .optional();
    }

    UUID insertInvitedEmployee(
            UUID organizationId,
            String employeeCode,
            String name,
            String email,
            String emailNormalized,
            String role,
            LocalDate joinDate) {
        return jdbc.sql(INSERT_EMPLOYEE)
                .param(organizationId)
                .param(employeeCode)
                .param(name)
                .param(email)
                .param(emailNormalized)
                .param(role)
                .param(joinDate)
                .query(UUID.class)
                .single();
    }

    boolean employeeCodeExists(UUID organizationId, String employeeCode) {
        return jdbc.sql(EMPLOYEE_CODE_EXISTS)
                .param(organizationId)
                .param(employeeCode)
                .query(Boolean.class)
                .single();
    }

    boolean hasOpenInvitation(UUID organizationId, String emailNormalized) {
        return jdbc.sql(HAS_OPEN_INVITATION)
                .param(organizationId)
                .param(emailNormalized)
                .query(Boolean.class)
                .single();
    }

    UUID insertInvitation(
            UUID organizationId,
            String emailNormalized,
            String role,
            String tokenHash,
            UUID inviterId,
            Instant expiresAt) {
        return jdbc.sql(INSERT_INVITATION)
                .param(organizationId)
                .param(emailNormalized)
                .param(role)
                .param(tokenHash)
                .param(inviterId)
                .param(Timestamp.from(expiresAt))
                .query(UUID.class)
                .single();
    }

    /** For preview: a plain read, nothing locked. */
    Optional<TokenInvitation> byTokenHash(UUID organizationId, String tokenHash) {
        return queryByTokenHash(SELECT_BY_TOKEN_HASH, organizationId, tokenHash);
    }

    /**
     * For acceptance: the invitation and employee rows stay locked until the transaction ends, so
     * two acceptances of one token run one after the other and only the first succeeds.
     */
    Optional<TokenInvitation> byTokenHashForUpdate(UUID organizationId, String tokenHash) {
        return queryByTokenHash(
                SELECT_BY_TOKEN_HASH + " FOR UPDATE OF i, e", organizationId, tokenHash);
    }

    private Optional<TokenInvitation> queryByTokenHash(
            String sql, UUID organizationId, String tokenHash) {
        return jdbc.sql(sql)
                .param(organizationId)
                .param(tokenHash)
                .query(
                        (rs, rowNum) ->
                                new TokenInvitation(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("organization_id", UUID.class),
                                        rs.getString("intended_role"),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getTimestamp("consumed_at") != null,
                                        rs.getTimestamp("revoked_at") != null,
                                        rs.getString("organization_name"),
                                        rs.getString("organization_status"),
                                        rs.getObject("employee_id", UUID.class),
                                        rs.getString("employee_name"),
                                        rs.getString("employee_email"),
                                        rs.getString("employee_status"),
                                        rs.getString("employee_role")))
                .optional();
    }

    /** Marks the invitation used; false if it was already consumed or revoked. */
    boolean consume(UUID invitationId, Instant at) {
        return jdbc.sql(CONSUME_INVITATION).param(Timestamp.from(at)).param(invitationId).update()
                == 1;
    }

    /** Sets the password and activates an {@code INVITED} employee; false if not invited. */
    boolean activate(UUID employeeId, UUID organizationId, String passwordHash, Instant at) {
        return jdbc.sql(ACTIVATE_EMPLOYEE)
                        .param(passwordHash)
                        .param(Timestamp.from(at))
                        .param(employeeId)
                        .param(organizationId)
                        .update()
                == 1;
    }

    Optional<ManagedInvitation> byIdForUpdate(UUID invitationId, UUID organizationId) {
        return jdbc.sql(SELECT_BY_ID_FOR_UPDATE)
                .param(invitationId)
                .param(organizationId)
                .query(
                        (rs, rowNum) ->
                                new ManagedInvitation(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("email_normalized"),
                                        rs.getString("intended_role"),
                                        rs.getTimestamp("consumed_at") != null,
                                        rs.getTimestamp("revoked_at") != null,
                                        rs.getObject("employee_id", UUID.class),
                                        rs.getString("employee_name"),
                                        rs.getString("employee_email"),
                                        rs.getString("employee_status")))
                .optional();
    }

    /** Revokes an open invitation of the organization; false if it was not open. */
    boolean revoke(UUID invitationId, UUID organizationId, Instant at) {
        return jdbc.sql(REVOKE_INVITATION)
                        .param(Timestamp.from(at))
                        .param(invitationId)
                        .param(organizationId)
                        .update()
                == 1;
    }

    /** Revokes every open invitation of one email in one organization; returns how many. */
    int revokeOpenFor(UUID organizationId, String emailNormalized, Instant at) {
        return jdbc.sql(REVOKE_OPEN_FOR_EMAIL)
                .param(Timestamp.from(at))
                .param(organizationId)
                .param(emailNormalized)
                .update();
    }

    record Organization(String name, String loginKey) {}

    /** An invitation as an Admin manages it (resend, revoke). */
    record ManagedInvitation(
            UUID id,
            String emailNormalized,
            String role,
            boolean consumed,
            boolean revoked,
            UUID employeeId,
            String employeeName,
            String employeeEmail,
            String employeeStatus) {

        /** Neither accepted nor revoked, and its person is still only invited (B2-4/O8). */
        boolean isOpen() {
            return !consumed && !revoked && "INVITED".equals(employeeStatus);
        }
    }

    /** An invitation found by its token, with what acceptance and preview need. */
    record TokenInvitation(
            UUID id,
            UUID organizationId,
            String role,
            Instant expiresAt,
            boolean consumed,
            boolean revoked,
            String organizationName,
            String organizationStatus,
            UUID employeeId,
            String employeeName,
            String employeeEmail,
            String employeeStatus,
            String employeeRole) {

        /** Everything must hold for the token to be usable (B2-4/O4). */
        boolean isUsableAt(Instant now) {
            return !consumed
                    && !revoked
                    && expiresAt.isAfter(now)
                    && "ACTIVE".equals(organizationStatus)
                    && "INVITED".equals(employeeStatus)
                    && role.equals(employeeRole);
        }
    }

    record Employee(UUID id, String name, String email, String status, String role) {}
}
