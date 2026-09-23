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

    record Organization(String name, String loginKey) {}

    record Employee(UUID id, String name, String email, String status, String role) {}
}
