package com.peoplehub.employee;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.auth.RefreshTokenService;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.mfa.StepUps;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.net.InetAddress;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promoting an existing Employee to Admin (b2-7, B2-7/17, B2-7/18, B2-7/19; D25; Spec 3.2, 3.3,
 * 8.3, 13.0).
 *
 * <p>Checks, in the order of deactivation (B2-6): only a Super Admin (403, before any lookup); the
 * target inside the caller's own organization, row-locked (404 for an unknown id and another
 * organization's alike); not the caller themselves (403); a fresh step-up ({@code
 * step-up-required}, or {@code mfa-enrollment-required} when the policy requires MFA of the caller,
 * who has not enrolled); and the target is an {@code ACTIVE} {@code EMPLOYEE} (409).
 *
 * <p>Promotion makes the role {@code ADMIN} and ends every session of the target ({@code
 * ROLE_CHANGED}), so the new role applies at their next sign-in; the per-request check reads the
 * role from the database anyway (B2-3/14). MFA is <em>not</em> a precondition: if the
 * organization's policy requires MFA of Admins and the person has not enrolled, their next sign-in
 * is the {@code ENROLL} step (B2-7/9), before any Admin operation; if they are enrolled, they are
 * challenged as always. Demotion and granting Super Admin are not built here (B2-7/19, b3-1/b3-4).
 */
@Service
public class EmployeePromotionService {

    static final String NOT_PROMOTABLE = "Only an active Employee can be promoted to Admin.";

    private final EmployeeLifecycleStore store;
    private final StepUps stepUps;
    private final RefreshTokenService refreshTokenService;
    private final AuditWriter auditWriter;

    EmployeePromotionService(
            EmployeeLifecycleStore store,
            StepUps stepUps,
            RefreshTokenService refreshTokenService,
            AuditWriter auditWriter) {
        this.store = store;
        this.stepUps = stepUps;
        this.refreshTokenService = refreshTokenService;
        this.auditWriter = auditWriter;
    }

    @Transactional
    public void promoteToAdmin(AuthenticatedPrincipal caller, UUID employeeId, InetAddress ip) {
        if (!caller.isSuperAdmin()) {
            throw forbidden();
        }
        EmployeeLifecycleStore.Target target =
                store.lockTarget(employeeId, caller.organizationId())
                        .orElseThrow(
                                () ->
                                        new ApiProblemException(
                                                ProblemType.NOT_FOUND,
                                                EmployeeLifecycleService.NOT_FOUND));
        if (target.id().equals(caller.employeeId())) {
            throw forbidden();
        }
        stepUps.requireFresh(caller);
        if (!"ACTIVE".equals(target.status()) || !"EMPLOYEE".equals(target.role())) {
            throw new ApiProblemException(ProblemType.CONFLICT, NOT_PROMOTABLE);
        }

        store.promoteToAdmin(target.id(), caller.organizationId());
        int revoked =
                refreshTokenService.endAllSessionsAfterRoleChange(
                        caller.organizationId(), target.id());
        auditWriter.append(
                AuditEvent.builder(caller.organizationId(), "EMPLOYEE_PROMOTED")
                        .target(AuditTarget.of("EMPLOYEE", target.id().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("fromRole", "EMPLOYEE")
                                        .attribute("toRole", "ADMIN")
                                        .attribute("revokedSessions", revoked)
                                        .build())
                        .build());
    }

    private static ApiProblemException forbidden() {
        return new ApiProblemException(ProblemType.FORBIDDEN, EmployeeLifecycleService.FORBIDDEN);
    }
}
