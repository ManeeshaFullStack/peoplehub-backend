package com.peoplehub.mfa;

import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step-up authentication state (b2-7, B2-7/14, B2-7/15, B2-7/16; Spec 8.3; V22 {@code
 * session_step_up}).
 *
 * <p>A successful step-up is one row bound to the organization, the employee <em>and the calling
 * session</em> (the refresh-token family, the access token's {@code sid}): a step-up in one session
 * never authorizes another, and it ends with its session. It is fresh for {@value #FRESH_MINUTES}
 * minutes from {@code verified_at}, the database's time, so freshness is also decided on the
 * database's clock.
 *
 * <p>{@link #requireFresh} is the guard in front of every step-up protected action:
 *
 * <ul>
 *   <li>a caller the organization's policy requires to have MFA, who has not enrolled, gets 403
 *       {@code mfa-enrollment-required}: they enroll first;
 *   <li>otherwise, without a fresh step-up of this session, 403 {@code step-up-required};
 *   <li>a caller who has MFA enabled needs a step-up that proved it: a password-only step-up (made
 *       before they enrolled, for example) does not count.
 * </ul>
 */
@Component
public class StepUps {

    static final int FRESH_MINUTES = 5;

    /** How a step-up was proven ({@code session_step_up.method}). */
    public enum Method {
        PASSWORD,
        PASSWORD_AND_TOTP,
        PASSWORD_AND_RECOVERY_CODE
    }

    static final String STEP_UP_REQUIRED =
            "This action needs you to confirm it's you. Verify again and retry.";
    public static final String ENROLLMENT_REQUIRED =
            "Your organization requires MFA for your account. Set it up first.";

    private static final String INSERT =
            "INSERT INTO session_step_up (organization_id, employee_id, session_id, method)"
                    + " VALUES (?, ?, ?, ?)";

    private static final String SELECT_MFA_STATE =
            "SELECT e.mfa_enabled, e.mfa_required, o.mfa_policy"
                    + " FROM employee e JOIN organization o ON o.id = e.organization_id"
                    + " WHERE e.id = ? AND e.organization_id = ?";

    private static final String FRESH =
            "SELECT EXISTS (SELECT 1 FROM session_step_up"
                    + " WHERE organization_id = ? AND employee_id = ? AND session_id = ?"
                    + " AND verified_at > now() - make_interval(mins => ?)"
                    + " AND (? OR method <> 'PASSWORD'))";

    private final JdbcClient jdbc;

    public StepUps(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The caller's MFA situation, for the step-up rules. */
    public record MfaState(boolean enabled, boolean required) {}

    /** Whether MFA is enabled for the caller, and whether the policy requires it of them. */
    @Transactional(propagation = Propagation.MANDATORY)
    public MfaState mfaState(AuthenticatedPrincipal caller) {
        return jdbc.sql(SELECT_MFA_STATE)
                .param(caller.employeeId())
                .param(caller.organizationId())
                .query(
                        (rs, rowNum) ->
                                new MfaState(
                                        rs.getBoolean("mfa_enabled"),
                                        MfaPolicy.valueOf(rs.getString("mfa_policy"))
                                                .covers(
                                                        caller.role(),
                                                        rs.getBoolean("mfa_required"))))
                .single();
    }

    /** Records a successful step-up of the caller's current session. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuthenticatedPrincipal caller, Method method) {
        jdbc.sql(INSERT)
                .param(caller.organizationId())
                .param(caller.employeeId())
                .param(caller.sessionId())
                .param(method.name())
                .update();
    }

    /** Throws the matching 403 unless the caller's session has a fresh, sufficient step-up. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFresh(AuthenticatedPrincipal caller) {
        MfaState state = mfaState(caller);
        if (!state.enabled() && state.required()) {
            throw new ApiProblemException(ProblemType.MFA_ENROLLMENT_REQUIRED, ENROLLMENT_REQUIRED);
        }
        boolean fresh =
                jdbc.sql(FRESH)
                        .param(caller.organizationId())
                        .param(caller.employeeId())
                        .param(caller.sessionId())
                        .param(FRESH_MINUTES)
                        .param(!state.enabled())
                        .query(Boolean.class)
                        .single();
        if (!fresh) {
            throw new ApiProblemException(ProblemType.STEP_UP_REQUIRED, STEP_UP_REQUIRED);
        }
    }
}
