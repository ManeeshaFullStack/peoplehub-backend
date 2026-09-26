package com.peoplehub.mfa.admin;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.auth.RefreshTokenService;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.mfa.MfaChallenges;
import com.peoplehub.mfa.MfaPolicy;
import com.peoplehub.mfa.RecoveryCodeStore;
import com.peoplehub.mfa.StepUps;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organization MFA administration (b2-7, B2-7/1, B2-7/3, B2-7/12, B2-7/14; MFA/4, MFA/5; Spec 3.2,
 * 8.3): the organization's MFA policy, the Super Admin's selection of people for {@code
 * REQUIRED_FOR_SELECTED_USERS}, and resetting another person's MFA. Authorization is a minimal
 * check on the caller's current role (the b3-1 matrix comes later); tenant scope comes only from
 * the token, and a target employee is looked up inside the caller's organization, so an unknown id
 * and another organization's are the same 404.
 *
 * <p><b>Newly required, no grace period (B2-7/3).</b> When a policy change or a selection makes MFA
 * required of someone who is not enrolled and was not required before, all their sessions end
 * ({@code MFA_REQUIRED}), so the requirement applies at their next sign-in (the {@code ENROLL}
 * step, B2-7/9). The acting Super Admin keeps their own current session; if the change requires MFA
 * of them, the step-up guard makes them enroll before any protected action ({@link StepUps}).
 * People already enrolled are unaffected: they are challenged at sign-in anyway (B2-7/2).
 *
 * <p><b>Locking.</b> A policy change locks the organization row, then, in id order, every employee
 * of the organization without MFA, before reading their role and selection; a selection locks its
 * target. Login and refresh lock the employee row before creating or rotating a token (B2-6/12), so
 * a sign-in that raced the change either finished first (and its new token is revoked here) or
 * waits and then sees the new policy.
 */
@Service
public class MfaAdministrationService {

    static final String NOT_FOUND = "This employee was not found.";
    static final String FORBIDDEN = "You are not allowed to perform this action.";
    static final String NOTHING_TO_RESET = "This employee has no MFA to reset.";

    private static final String LOCK_POLICY =
            "SELECT mfa_policy FROM organization WHERE id = ? FOR UPDATE";

    private static final String SET_POLICY =
            "UPDATE organization SET mfa_policy = ?, updated_at = ? WHERE id = ?";

    private static final String LOCK_NOT_ENROLLED =
            "SELECT id, role, mfa_required FROM employee"
                    + " WHERE organization_id = ? AND NOT mfa_enabled ORDER BY id FOR UPDATE";

    private static final String LOCK_TARGET =
            "SELECT id, role, mfa_enabled, mfa_required FROM employee"
                    + " WHERE id = ? AND organization_id = ? FOR UPDATE";

    private static final String SELECT_POLICY = "SELECT mfa_policy FROM organization WHERE id = ?";

    private static final String SET_REQUIRED =
            "UPDATE employee SET mfa_required = ?, updated_at = ?"
                    + " WHERE id = ? AND organization_id = ?";

    private static final String RESET =
            "UPDATE employee SET mfa_enabled = false, mfa_totp_secret = NULL,"
                    + " mfa_totp_pending_secret = NULL, mfa_enrolled_at = NULL, updated_at = ?"
                    + " WHERE id = ? AND organization_id = ?";

    private final JdbcClient jdbc;
    private final StepUps stepUps;
    private final RefreshTokenService refreshTokenService;
    private final RecoveryCodeStore recoveryCodeStore;
    private final MfaChallenges challenges;
    private final AuditWriter auditWriter;
    private final Clock clock;

    MfaAdministrationService(
            JdbcClient jdbc,
            StepUps stepUps,
            RefreshTokenService refreshTokenService,
            RecoveryCodeStore recoveryCodeStore,
            MfaChallenges challenges,
            AuditWriter auditWriter,
            Clock clock) {
        this.jdbc = jdbc;
        this.stepUps = stepUps;
        this.refreshTokenService = refreshTokenService;
        this.recoveryCodeStore = recoveryCodeStore;
        this.challenges = challenges;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    /**
     * Sets the organization's MFA policy (Super Admin, step-up; B2-7/3). Setting the current policy
     * again changes nothing and writes no audit row.
     */
    @Transactional
    public void changePolicy(AuthenticatedPrincipal caller, MfaPolicy policy, InetAddress ip) {
        if (!caller.isSuperAdmin()) {
            throw forbidden();
        }
        stepUps.requireFresh(caller);
        UUID organizationId = caller.organizationId();
        MfaPolicy before =
                MfaPolicy.valueOf(
                        jdbc.sql(LOCK_POLICY).param(organizationId).query(String.class).single());
        if (before == policy) {
            return;
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql(SET_POLICY).param(policy.name()).param(now).param(organizationId).update();

        int newlyRequired = 0;
        int revoked = 0;
        for (Candidate candidate : lockNotEnrolled(organizationId)) {
            if (policy.covers(candidate.role(), candidate.selected())
                    && !before.covers(candidate.role(), candidate.selected())) {
                newlyRequired++;
                revoked += endSessions(caller, candidate.id());
            }
        }
        auditWriter.append(
                AuditEvent.builder(organizationId, "MFA_POLICY_CHANGED")
                        .target(AuditTarget.of("ORGANIZATION", organizationId.toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .change("mfaPolicy", before.name(), policy.name())
                                        .attribute("newlyRequired", newlyRequired)
                                        .attribute("revokedSessions", revoked)
                                        .build())
                        .build());
    }

    /**
     * Selects or unselects a person for {@code REQUIRED_FOR_SELECTED_USERS} (Super Admin, step-up).
     * Settable under any policy; it only requires MFA while the policy is {@code
     * REQUIRED_FOR_SELECTED_USERS}. Setting the current value again changes nothing.
     */
    @Transactional
    public void setRequired(
            AuthenticatedPrincipal caller, UUID employeeId, boolean required, InetAddress ip) {
        if (!caller.isSuperAdmin()) {
            throw forbidden();
        }
        Target target = lockTarget(caller, employeeId);
        stepUps.requireFresh(caller);
        if (target.selected() == required) {
            return;
        }
        UUID organizationId = caller.organizationId();
        jdbc.sql(SET_REQUIRED)
                .param(required)
                .param(Timestamp.from(clock.instant()))
                .param(target.id())
                .param(organizationId)
                .update();
        MfaPolicy policy =
                MfaPolicy.valueOf(
                        jdbc.sql(SELECT_POLICY).param(organizationId).query(String.class).single());
        boolean newlyRequired =
                required && policy == MfaPolicy.REQUIRED_FOR_SELECTED_USERS && !target.enabled();
        int revoked = newlyRequired ? endSessions(caller, target.id()) : 0;
        auditWriter.append(
                AuditEvent.builder(organizationId, "MFA_SELECTION_CHANGED")
                        .target(AuditTarget.of("EMPLOYEE", target.id().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .change(
                                                "mfaRequired",
                                                Boolean.toString(target.selected()),
                                                Boolean.toString(required))
                                        .attribute("newlyRequired", newlyRequired)
                                        .attribute("revokedSessions", revoked)
                                        .build())
                        .build());
    }

    /**
     * Resets another person's MFA (B2-7/12; Spec 3.2, 8.3): an Admin may reset an Employee's, a
     * Super Admin an Admin's or an Employee's; nobody a Super Admin's or their own (403). Needs a
     * step-up, and a target with MFA enabled (409). Clears the secrets and the enrollment,
     * invalidates the unused recovery codes and every open sign-in challenge, and ends every
     * session ({@code MFA_RESET}); the person enrolls again at their next sign-in if the policy
     * requires it.
     */
    @Transactional
    public void reset(AuthenticatedPrincipal caller, UUID employeeId, InetAddress ip) {
        if (!caller.isAdmin()) {
            throw forbidden();
        }
        Target target = lockTarget(caller, employeeId);
        boolean allowed =
                !target.id().equals(caller.employeeId())
                        && !"SUPER_ADMIN".equals(target.role())
                        && (caller.isSuperAdmin() || "EMPLOYEE".equals(target.role()));
        if (!allowed) {
            throw forbidden();
        }
        stepUps.requireFresh(caller);
        if (!target.enabled()) {
            throw new ApiProblemException(ProblemType.CONFLICT, NOTHING_TO_RESET);
        }
        UUID organizationId = caller.organizationId();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql(RESET).param(now).param(target.id()).param(organizationId).update();
        int codes = recoveryCodeStore.invalidateUnused(organizationId, target.id(), now);
        challenges.invalidateOpen(organizationId, target.id());
        int revoked = refreshTokenService.endAllSessionsAfterMfaReset(organizationId, target.id());
        auditWriter.append(
                AuditEvent.builder(organizationId, "MFA_RESET")
                        .target(AuditTarget.of("EMPLOYEE", target.id().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("role", target.role())
                                        .attribute("revokedSessions", revoked)
                                        .attribute("codesInvalidated", codes)
                                        .build())
                        .build());
    }

    /** Ends a newly required person's sessions; the acting Super Admin keeps this session. */
    private int endSessions(AuthenticatedPrincipal caller, UUID employeeId) {
        UUID kept = employeeId.equals(caller.employeeId()) ? caller.sessionId() : null;
        return refreshTokenService.endSessionsWhenMfaBecomesRequired(
                caller.organizationId(), employeeId, kept);
    }

    private List<Candidate> lockNotEnrolled(UUID organizationId) {
        return jdbc.sql(LOCK_NOT_ENROLLED)
                .param(organizationId)
                .query(
                        (rs, rowNum) ->
                                new Candidate(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("role"),
                                        rs.getBoolean("mfa_required")))
                .list();
    }

    private Target lockTarget(AuthenticatedPrincipal caller, UUID employeeId) {
        return jdbc.sql(LOCK_TARGET)
                .param(employeeId)
                .param(caller.organizationId())
                .query(
                        (rs, rowNum) ->
                                new Target(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("role"),
                                        rs.getBoolean("mfa_enabled"),
                                        rs.getBoolean("mfa_required")))
                .optional()
                .orElseThrow(() -> new ApiProblemException(ProblemType.NOT_FOUND, NOT_FOUND));
    }

    private static ApiProblemException forbidden() {
        return new ApiProblemException(ProblemType.FORBIDDEN, FORBIDDEN);
    }

    private record Candidate(UUID id, String role, boolean selected) {}

    private record Target(UUID id, String role, boolean enabled, boolean selected) {}
}
