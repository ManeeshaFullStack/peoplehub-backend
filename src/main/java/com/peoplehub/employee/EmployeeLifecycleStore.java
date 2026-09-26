package com.peoplehub.employee;

import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Plain JDBC access to the employee columns deactivation and reactivation change (b2-6, B2-6/10,
 * 11, 13): {@code status} and {@code exit_date}; and, for promotion (b2-7, B2-7/18), {@code role}.
 * Every query is qualified by the caller's organization, so another organization's employee id
 * finds nothing (Spec 15.1, D22).
 */
@Component
class EmployeeLifecycleStore {

    /** The target inside the caller's organization, row-locked until the transaction ends. */
    private static final String SELECT_FOR_UPDATE =
            "SELECT e.id, e.role, e.status, e.email_normalized,"
                    + " e.password_hash IS NOT NULL AS has_password, o.timezone"
                    + " FROM employee e JOIN organization o ON o.id = e.organization_id"
                    + " WHERE e.id = ? AND e.organization_id = ? FOR UPDATE OF e";

    private static final String DEACTIVATE =
            "UPDATE employee SET status = 'DEACTIVATED', exit_date = coalesce(?, exit_date)"
                    + " WHERE id = ? AND organization_id = ?";

    private static final String REACTIVATE =
            "UPDATE employee SET status = ?, exit_date = NULL WHERE id = ? AND organization_id = ?";

    /** b2-7 (B2-7/18): Employee to Admin. */
    private static final String PROMOTE_TO_ADMIN =
            "UPDATE employee SET role = 'ADMIN' WHERE id = ? AND organization_id = ?";

    private final JdbcClient jdbc;

    EmployeeLifecycleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Target> lockTarget(UUID employeeId, UUID organizationId) {
        return jdbc.sql(SELECT_FOR_UPDATE)
                .param(employeeId)
                .param(organizationId)
                .query(
                        (rs, rowNum) ->
                                new Target(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("role"),
                                        rs.getString("status"),
                                        rs.getString("email_normalized"),
                                        rs.getBoolean("has_password"),
                                        ZoneId.of(rs.getString("timezone"))))
                .optional();
    }

    void deactivate(UUID employeeId, UUID organizationId, LocalDate exitDate) {
        jdbc.sql(DEACTIVATE)
                .param(
                        new SqlParameterValue(
                                Types.DATE, exitDate == null ? null : Date.valueOf(exitDate)))
                .param(employeeId)
                .param(organizationId)
                .update();
    }

    void reactivate(UUID employeeId, UUID organizationId, String status) {
        jdbc.sql(REACTIVATE).param(status).param(employeeId).param(organizationId).update();
    }

    void promoteToAdmin(UUID employeeId, UUID organizationId) {
        jdbc.sql(PROMOTE_TO_ADMIN).param(employeeId).param(organizationId).update();
    }

    record Target(
            UUID id,
            String role,
            String status,
            String emailNormalized,
            boolean hasPassword,
            ZoneId timezone) {

        @Override
        public String toString() {
            return "Target[id=" + id + ", role=" + role + ", status=" + status + "]";
        }
    }
}
