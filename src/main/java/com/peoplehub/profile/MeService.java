package com.peoplehub.profile;

import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.sql.Date;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the caller's own profile (b2-3, B2-3/19). The employee id and the organization come only
 * from the {@link AuthenticatedPrincipal} (Spec 3.2: self-service endpoints never take an id from
 * the request), and the lookup is qualified by both, so it can only ever return the caller's own
 * row in the caller's own tenant (Spec 15.1).
 */
@Service
public class MeService {

    private static final String SELECT_ME =
            "SELECT e.id, e.name, e.email, e.role, e.status, e.join_date, o.name AS org_name,"
                    + " o.timezone, o.onboarding_completed_at IS NOT NULL AS onboarding_completed"
                    + " FROM employee e JOIN organization o ON o.id = e.organization_id"
                    + " WHERE e.id = ? AND e.organization_id = ?";

    private final JdbcClient jdbc;

    public MeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public MeResponse me(AuthenticatedPrincipal principal) {
        return jdbc.sql(SELECT_ME)
                .param(principal.employeeId())
                .param(principal.organizationId())
                .query(
                        (rs, rowNum) -> {
                            Date joinDate = rs.getDate("join_date");
                            return new MeResponse(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("name"),
                                    rs.getString("email"),
                                    rs.getString("role"),
                                    rs.getString("status"),
                                    joinDate == null ? null : joinDate.toLocalDate(),
                                    new MeResponse.Organization(
                                            rs.getString("org_name"),
                                            rs.getString("timezone"),
                                            rs.getBoolean("onboarding_completed")));
                        })
                .optional()
                // Only possible if the row vanished between authentication and this read: answer
                // like any other failed authentication.
                .orElseThrow(
                        () ->
                                new ApiProblemException(
                                        ProblemType.UNAUTHORIZED,
                                        "Authentication is required to access this resource."));
    }
}
