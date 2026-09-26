package com.peoplehub.mfa;

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
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own step-up protected MFA operations (b2-7, B2-7/8, B2-7/12, B2-7/14; Spec 8.3). The
 * account is always the caller's own, from the token; the employee row is locked first.
 *
 * <ul>
 *   <li>{@link #disable}: refused (409) while MFA is not enabled, and while the organization's
 *       policy requires MFA of the caller (B2-7/12); otherwise, after a fresh step-up, it clears
 *       the active and any pending secret and the enrollment time, invalidates every unused
 *       recovery code and every open sign-in challenge, and audits {@code MFA_DISABLED}. Sessions
 *       stay; the caller becomes eligible for the reminder again (B2-7/27).
 *   <li>{@link #regenerateRecoveryCodes}: refused (409) while MFA is not enabled; otherwise, after
 *       a fresh step-up, it invalidates every unused code (never deleting a row), issues ten new
 *       ones returned once, and audits {@code MFA_RECOVERY_CODES_REGENERATED}.
 * </ul>
 *
 * The state checks (409) come before the step-up check (403), so a caller is never asked to step up
 * for something that cannot be done.
 */
@Service
public class MfaSelfService {

    static final String NOT_ENABLED = "MFA is not enabled for your account.";
    static final String REQUIRED = "Your organization requires MFA for your account.";

    private static final String LOCK_EMPLOYEE =
            "SELECT id FROM employee WHERE id = ? AND organization_id = ? FOR UPDATE";

    private static final String DISABLE =
            "UPDATE employee SET mfa_enabled = false, mfa_totp_secret = NULL,"
                    + " mfa_totp_pending_secret = NULL, mfa_enrolled_at = NULL, updated_at = ?"
                    + " WHERE id = ? AND organization_id = ? AND mfa_enabled";

    private final JdbcClient jdbc;
    private final StepUps stepUps;
    private final RecoveryCodeStore recoveryCodeStore;
    private final MfaChallenges challenges;
    private final AuditWriter auditWriter;
    private final Clock clock;

    MfaSelfService(
            JdbcClient jdbc,
            StepUps stepUps,
            RecoveryCodeStore recoveryCodeStore,
            MfaChallenges challenges,
            AuditWriter auditWriter,
            Clock clock) {
        this.jdbc = jdbc;
        this.stepUps = stepUps;
        this.recoveryCodeStore = recoveryCodeStore;
        this.challenges = challenges;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    /** Turns off the caller's own MFA. */
    @Transactional
    public void disable(AuthenticatedPrincipal caller, InetAddress ip) {
        StepUps.MfaState state = lockAndRead(caller);
        if (!state.enabled()) {
            throw conflict(NOT_ENABLED);
        }
        if (state.required()) {
            throw conflict(REQUIRED);
        }
        stepUps.requireFresh(caller);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql(DISABLE)
                .param(now)
                .param(caller.employeeId())
                .param(caller.organizationId())
                .update();
        int codes =
                recoveryCodeStore.invalidateUnused(
                        caller.organizationId(), caller.employeeId(), now);
        challenges.invalidateOpen(caller.organizationId(), caller.employeeId());
        auditWriter.append(
                AuditEvent.builder(caller.organizationId(), "MFA_DISABLED")
                        .target(AuditTarget.of("EMPLOYEE", caller.employeeId().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder().attribute("codesInvalidated", codes).build())
                        .build());
    }

    /** Replaces the caller's recovery codes; returns the new ones, once. */
    @Transactional
    public MfaRecoveryCodesResponse regenerateRecoveryCodes(
            AuthenticatedPrincipal caller, InetAddress ip) {
        StepUps.MfaState state = lockAndRead(caller);
        if (!state.enabled()) {
            throw conflict(NOT_ENABLED);
        }
        stepUps.requireFresh(caller);
        List<String> codes =
                recoveryCodeStore.replace(
                        caller.organizationId(),
                        caller.employeeId(),
                        Timestamp.from(clock.instant()));
        auditWriter.append(
                AuditEvent.builder(caller.organizationId(), "MFA_RECOVERY_CODES_REGENERATED")
                        .target(AuditTarget.of("EMPLOYEE", caller.employeeId().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("codesIssued", codes.size())
                                        .build())
                        .build());
        return new MfaRecoveryCodesResponse(codes);
    }

    private StepUps.MfaState lockAndRead(AuthenticatedPrincipal caller) {
        jdbc.sql(LOCK_EMPLOYEE)
                .param(caller.employeeId())
                .param(caller.organizationId())
                .query(Object.class)
                .single();
        return stepUps.mfaState(caller);
    }

    private static ApiProblemException conflict(String detail) {
        return new ApiProblemException(ProblemType.CONFLICT, detail);
    }
}
