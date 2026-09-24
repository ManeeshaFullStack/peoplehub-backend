package com.peoplehub.employee;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.auth.RefreshTokenService;
import com.peoplehub.common.api.error.ApiFieldError;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.invitation.InvitationService;
import com.peoplehub.passwordreset.PasswordResetService;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.net.InetAddress;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deactivating and reactivating an employee (b2-6, B2-6/7-B2-6/11, B2-6/13, B2-6/15, B2-6/16; Spec
 * 2.1.7, 3.2, 3.3, D26).
 *
 * <p>Checks, in this order: the target is looked up (and row-locked) inside the caller's own
 * organization, so an unknown id or another organization's is the same 404; then who may act on
 * whom (403); then the target's state (409); then the request (400).
 *
 * <p>Who may act on whom (a minimal check on the caller's current role, not the b3-1 matrix): an
 * Admin acts on Employees only; a Super Admin on anyone; nobody on themselves. So deactivation can
 * never remove the last active Super Admin (B2-6/9): only a Super Admin can deactivate one, and
 * never themselves.
 *
 * <p>Deactivation ends every session, revokes an invitee's open invitation and invalidates unused
 * reset codes, in one transaction with the status change and its audit row. It deletes nothing: the
 * password, the lockout counters, invitations the person sent and every historical row stay.
 * Reactivation restores {@code ACTIVE} (or {@code INVITED} when no password was ever set) and
 * brings no session back.
 */
@Service
public class EmployeeLifecycleService {

    static final String NOT_FOUND = "This employee was not found.";
    static final String FORBIDDEN = "You are not allowed to perform this action.";

    private static final String ACTIVE = "ACTIVE";
    private static final String INVITED = "INVITED";
    private static final String DEACTIVATED = "DEACTIVATED";
    private static final String EMPLOYEE = "EMPLOYEE";
    private static final Set<String> DEACTIVATABLE = Set.of(ACTIVE, INVITED);

    private final EmployeeLifecycleStore store;
    private final RefreshTokenService refreshTokenService;
    private final InvitationService invitationService;
    private final PasswordResetService passwordResetService;
    private final AuditWriter auditWriter;
    private final Clock clock;

    public EmployeeLifecycleService(
            EmployeeLifecycleStore store,
            RefreshTokenService refreshTokenService,
            InvitationService invitationService,
            PasswordResetService passwordResetService,
            AuditWriter auditWriter,
            Clock clock) {
        this.store = store;
        this.refreshTokenService = refreshTokenService;
        this.invitationService = invitationService;
        this.passwordResetService = passwordResetService;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    @Transactional
    public void deactivate(
            AuthenticatedPrincipal caller,
            UUID employeeId,
            DeactivateEmployeeRequest request,
            InetAddress ip) {
        EmployeeLifecycleStore.Target target = authorizedTarget(caller, employeeId);
        if (!DEACTIVATABLE.contains(target.status())) {
            throw new ApiProblemException(
                    ProblemType.CONFLICT,
                    "This employee cannot be deactivated in their current state.");
        }
        LocalDate exitDate = request == null ? null : request.exitDate();
        if (exitDate != null
                && exitDate.isAfter(LocalDate.now(clock.withZone(target.timezone())))) {
            throw new ApiProblemException(
                    ProblemType.VALIDATION_ERROR,
                    "One or more fields are invalid.",
                    List.of(new ApiFieldError("exitDate", "must not be in the future")),
                    Map.of());
        }

        store.deactivate(target.id(), caller.organizationId(), exitDate);
        int revokedSessions =
                refreshTokenService.endAllSessionsOnDeactivation(
                        caller.organizationId(), target.id());
        boolean invitationRevoked =
                INVITED.equals(target.status())
                        && invitationService.revokeOpenInvitationOf(
                                caller.organizationId(), target.emailNormalized());
        passwordResetService.invalidateOpenResets(target.id(), caller.organizationId());

        auditWriter.append(
                AuditEvent.builder(caller.organizationId(), "EMPLOYEE_DEACTIVATED")
                        .target(AuditTarget.of("EMPLOYEE", target.id().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("role", target.role())
                                        .attribute("revokedSessions", revokedSessions)
                                        .attribute("invitationRevoked", invitationRevoked)
                                        .build())
                        .build());
    }

    @Transactional
    public void reactivate(AuthenticatedPrincipal caller, UUID employeeId, InetAddress ip) {
        EmployeeLifecycleStore.Target target = authorizedTarget(caller, employeeId);
        if (!DEACTIVATED.equals(target.status())) {
            throw new ApiProblemException(
                    ProblemType.CONFLICT, "Only a deactivated employee can be reactivated.");
        }
        String status = target.hasPassword() ? ACTIVE : INVITED;

        store.reactivate(target.id(), caller.organizationId(), status);
        // No session comes back: anything left over from before is ended too, as a safety net.
        refreshTokenService.endAllSessionsOnDeactivation(caller.organizationId(), target.id());

        auditWriter.append(
                AuditEvent.builder(caller.organizationId(), "EMPLOYEE_REACTIVATED")
                        .target(AuditTarget.of("EMPLOYEE", target.id().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("role", target.role())
                                        .attribute("status", status)
                                        .build())
                        .build());
    }

    /**
     * The locked target, after the 404 and 403 checks. A caller who is not an Admin or Super Admin
     * may act on nobody, so they get 403 without the lookup revealing anything.
     */
    private EmployeeLifecycleStore.Target authorizedTarget(
            AuthenticatedPrincipal caller, UUID employeeId) {
        if (!caller.isAdmin()) {
            throw forbidden();
        }
        EmployeeLifecycleStore.Target target =
                store.lockTarget(employeeId, caller.organizationId())
                        .orElseThrow(
                                () -> new ApiProblemException(ProblemType.NOT_FOUND, NOT_FOUND));
        boolean allowed =
                !target.id().equals(caller.employeeId())
                        && (caller.isSuperAdmin() || EMPLOYEE.equals(target.role()));
        if (!allowed) {
            throw forbidden();
        }
        return target;
    }

    private static ApiProblemException forbidden() {
        return new ApiProblemException(ProblemType.FORBIDDEN, FORBIDDEN);
    }
}
