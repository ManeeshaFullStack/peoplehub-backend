package com.peoplehub.organization;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completing the founder's first-time organization setup (b2-7, B2-7/21; Spec 2.1.3 step 8, 2.1.4,
 * 13.0): the step between email verification and the real Super Admin dashboard.
 *
 * <p>Super Admin only (403 otherwise). It records {@code organization.onboarding_completed_at}
 * once, at server time, and audits {@code ORGANIZATION_ONBOARDING_COMPLETED}; completing again
 * changes nothing (204, no second audit row). No step-up: it is not a security setting. The setup
 * steps it closes are the security step, which already has its own endpoints (the MFA policy,
 * B2-7/3, and the founder's own optional enrollment, B2-7/4); the other steps of Spec 2.1.4 need
 * the organization settings API (b3-6), and the frontend skips them until then. {@code GET /me}
 * shows the state as {@code organization.onboardingCompleted}. The organization is always the
 * caller's own.
 */
@Service
public class OnboardingService {

    static final String FORBIDDEN = "You are not allowed to perform this action.";

    private static final String COMPLETE =
            "UPDATE organization SET onboarding_completed_at = ?, updated_at = ?"
                    + " WHERE id = ? AND onboarding_completed_at IS NULL";

    private final JdbcClient jdbc;
    private final AuditWriter auditWriter;
    private final Clock clock;

    OnboardingService(JdbcClient jdbc, AuditWriter auditWriter, Clock clock) {
        this.jdbc = jdbc;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    @Transactional
    public void complete(AuthenticatedPrincipal caller, InetAddress ip) {
        if (!caller.isSuperAdmin()) {
            throw new ApiProblemException(ProblemType.FORBIDDEN, FORBIDDEN);
        }
        Timestamp now = Timestamp.from(clock.instant());
        int completed =
                jdbc.sql(COMPLETE).param(now).param(now).param(caller.organizationId()).update();
        if (completed == 0) {
            return;
        }
        auditWriter.append(
                AuditEvent.builder(caller.organizationId(), "ORGANIZATION_ONBOARDING_COMPLETED")
                        .target(AuditTarget.of("ORGANIZATION", caller.organizationId().toString()))
                        .ip(ip)
                        .details(AuditDetails.none())
                        .build());
    }
}
