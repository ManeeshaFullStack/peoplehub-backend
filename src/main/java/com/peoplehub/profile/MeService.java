package com.peoplehub.profile;

import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own profile and welcome state (b2-3, B2-3/19; b2-4, B2-4/O12). The employee id and
 * the organization come only from the {@link AuthenticatedPrincipal} (Spec 3.2: self-service
 * endpoints never take an id from the request), and every query is qualified by both, so it can
 * only ever read or change the caller's own row in the caller's own tenant (Spec 15.1).
 */
@Service
public class MeService {

    private static final String SELECT_ME =
            "SELECT e.id, e.name, e.email, e.role, e.status, e.join_date, e.welcome_seen_at,"
                    + " o.name AS org_name, o.timezone,"
                    + " o.onboarding_completed_at IS NOT NULL AS onboarding_completed"
                    + " FROM employee e JOIN organization o ON o.id = e.organization_id"
                    + " WHERE e.id = ? AND e.organization_id = ?";

    /** Only the first acknowledgement is recorded; later ones change nothing (Spec 10.2). */
    private static final String ACKNOWLEDGE_WELCOME =
            "UPDATE employee SET welcome_seen_at = ?, updated_at = ?"
                    + " WHERE id = ? AND organization_id = ? AND welcome_seen_at IS NULL";

    private final JdbcClient jdbc;
    private final Clock clock;

    public MeService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeResponse me(AuthenticatedPrincipal principal) {
        return jdbc.sql(SELECT_ME)
                .param(principal.employeeId())
                .param(principal.organizationId())
                .query(
                        (rs, rowNum) -> {
                            Date joinDate = rs.getDate("join_date");
                            Timestamp welcomeSeenAt = rs.getTimestamp("welcome_seen_at");
                            String name = rs.getString("name");
                            return new MeResponse(
                                    rs.getObject("id", UUID.class),
                                    name,
                                    firstName(name),
                                    rs.getString("email"),
                                    rs.getString("role"),
                                    rs.getString("status"),
                                    joinDate == null ? null : joinDate.toLocalDate(),
                                    welcomeSeenAt == null ? null : welcomeSeenAt.toInstant(),
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

    /**
     * Records that the caller has seen the one-time welcome screen (Spec 10.2, B2-4/O12), at server
     * time. Idempotent: once set, {@code welcome_seen_at} never changes.
     */
    @Transactional
    public void acknowledgeWelcome(AuthenticatedPrincipal principal) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql(ACKNOWLEDGE_WELCOME)
                .param(now)
                .param(now)
                .param(principal.employeeId())
                .param(principal.organizationId())
                .update();
    }

    /**
     * D17 / Spec 10.3: the first whitespace-delimited word of {@code name}, trimmed; the whole
     * (trimmed) name when it has no whitespace.
     */
    static String firstName(String name) {
        String trimmed = name.strip();
        return trimmed.split("\\s+", 2)[0];
    }
}
