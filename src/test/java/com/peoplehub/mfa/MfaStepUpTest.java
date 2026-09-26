package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.security.SecureTokens;
import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import com.peoplehub.support.TestMfaKeysEnvironment;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step-up authentication and the caller's own step-up protected MFA operations end to end (b2-7,
 * B2-7/6, B2-7/8, B2-7/12, B2-7/14, B2-7/15, B2-7/16, B2-7/24, B2-7/25; V22, V23; Spec 8.3): how a
 * step-up is proven for each MFA situation, that it is bound to one session and fresh for five
 * minutes, wrong passwords and codes and the lockout, replay and single use, recovery-code
 * regeneration, disabling MFA, and re-enrollment with the pending secret. Runs on a controllable
 * clock so TOTP steps are exact; step-up freshness is on the database's clock (V22).
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class MfaStepUpTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String STEP_UP = "/api/v1/me/step-up";
    private static final String REGENERATE = "/api/v1/me/mfa/recovery-codes/regenerate";
    private static final String DISABLE = "/api/v1/me/mfa/disable";
    private static final String ENROLL = "/api/v1/me/mfa/enroll";
    private static final String CONFIRM = "/api/v1/me/mfa/confirm";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private AccessTokenIssuer issuer;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private MfaSecretCipher cipher;
    @Autowired private RecoveryCodes recoveryCodes;
    @Autowired private SecureTokens secureTokens;

    private String passwordHash;

    @BeforeEach
    void now() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (passwordHash == null) {
            passwordHash = passwordHasher.hash(PASSWORD);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Proving a step-up
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(
            value = MfaPolicy.class,
            names = {"DISABLED", "OPTIONAL", "REQUIRED_FOR_ADMINS"})
    void withoutMfaEnabledOrRequiredThePasswordAloneStepsUp(MfaPolicy policy) throws Exception {
        Caller caller = caller(organization(policy), "EMPLOYEE");

        MvcResult result = stepUp(caller, PASSWORD, null, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(stepUpRows(caller))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("method")).isEqualTo("PASSWORD");
                            assertThat(row.get("organization_id"))
                                    .isEqualTo(caller.employee().organizationId());
                            assertThat(row.get("session_id")).isEqualTo(caller.session());
                        });
        Map<String, Object> audit = lastAudit(caller, "STEP_UP_VERIFIED");
        assertThat(attributes(audit))
                .containsEntry("method", "PASSWORD")
                .containsEntry("sessionId", caller.session().toString());
        assertThat(audit.get("details").toString()).doesNotContain(PASSWORD);
    }

    @Test
    void withMfaEnabledThePasswordAndATotpCodeAreNeeded() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(caller).secret();

        MvcResult passwordOnly = stepUp(caller, PASSWORD, null, null);
        assertThat(passwordOnly.getResponse().getStatus()).isEqualTo(400);
        assertThat(passwordOnly.getResponse().getContentAsString())
                .contains("\"field\":\"code\"")
                .contains("\"field\":\"recoveryCode\"");

        MvcResult withCode = stepUp(caller, PASSWORD, currentCode(secret), null);

        assertThat(withCode.getResponse().getStatus()).isEqualTo(204);
        assertThat(stepUpRows(caller))
                .singleElement()
                .extracting(r -> r.get("method"))
                .isEqualTo("PASSWORD_AND_TOTP");
        assertThat(lastStep(caller)).isEqualTo(Totp.step(clock.instant()));
    }

    @Test
    void aTotpCodeUsedForAStepUpIsNeverAcceptedAgain() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(caller).secret();
        String code = currentCode(secret);
        assertThat(stepUp(caller, PASSWORD, code, null).getResponse().getStatus()).isEqualTo(204);

        MvcResult replay = stepUp(caller, PASSWORD, code, null);

        assertThat(replay.getResponse().getStatus()).isEqualTo(400);
        assertThat(replay.getResponse().getContentAsString()).contains("\"field\":\"code\"");
        assertThat(stepUpRows(caller)).hasSize(1);
    }

    @Test
    void aRecoveryCodeStepsUpOnce() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        List<String> codes = enrolled(caller).recoveryCodes();

        MvcResult first = stepUp(caller, PASSWORD, null, codes.get(0));
        MvcResult again = stepUp(caller, PASSWORD, null, codes.get(0));

        assertThat(first.getResponse().getStatus()).isEqualTo(204);
        assertThat(again.getResponse().getStatus()).isEqualTo(400);
        assertThat(again.getResponse().getContentAsString()).contains("\"field\":\"recoveryCode\"");
        assertThat(stepUpRows(caller))
                .singleElement()
                .extracting(r -> r.get("method"))
                .isEqualTo("PASSWORD_AND_RECOVERY_CODE");
        assertThat(attributes(lastAudit(caller, "MFA_RECOVERY_CODE_USED")))
                .containsEntry("codesLeft", 9);
    }

    @Test
    void aRequiredPersonWhoHasNotEnrolledMustEnrollBeforeSteppingUp() throws Exception {
        Caller caller = caller(organization(MfaPolicy.REQUIRED_FOR_ADMINS), "ADMIN");

        MvcResult result = stepUp(caller, PASSWORD, null, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result))
                .containsEntry("type", "urn:peoplehub:problem:mfa-enrollment-required");
        assertThat(stepUpRows(caller)).isEmpty();
        assertThat(failedLoginCount(caller)).isZero();
    }

    @Test
    void aWrongPasswordOrCodeIsCountedTowardTheLockout() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        Enrollment enrollment = enrolled(caller);

        MvcResult wrongPassword =
                stepUp(caller, "wrong-password-123", null, enrollment.recoveryCodes().get(0));
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(400);
        assertThat(wrongPassword.getResponse().getContentAsString())
                .contains("\"field\":\"password\"");
        assertThat(failedLoginCount(caller)).isEqualTo(1);
        // With a wrong password, the code is never looked at: it is still unused.
        assertThat(unusedRecoveryCodes(caller)).isEqualTo(10);

        MvcResult wrongCode = stepUp(caller, PASSWORD, wrongCode(enrollment.secret()), null);
        assertThat(wrongCode.getResponse().getStatus()).isEqualTo(400);
        assertThat(wrongCode.getResponse().getContentAsString()).contains("\"field\":\"code\"");
        assertThat(failedLoginCount(caller)).isEqualTo(2);
        assertThat(stepUpRows(caller)).isEmpty();
        // A successful step-up does not clear the count; only a completed sign-in does.
        assertThat(
                        stepUp(caller, PASSWORD, currentCode(enrollment.secret()), null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        assertThat(failedLoginCount(caller)).isEqualTo(2);
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountAndALockedAccountCannotStepUp() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        for (int i = 0; i < 5; i++) {
            stepUp(caller, "wrong-password-123", null, null);
        }
        assertThat(auditCount(caller, "ACCOUNT_LOCKED")).isEqualTo(1);
        int counted = failedLoginCount(caller);

        MvcResult locked = stepUp(caller, PASSWORD, null, null);

        assertThat(locked.getResponse().getStatus()).isEqualTo(400);
        assertThat(locked.getResponse().getContentAsString()).contains("\"field\":\"password\"");
        assertThat(failedLoginCount(caller)).isEqualTo(counted); // not counted while locked
        assertThat(stepUpRows(caller)).isEmpty();
    }

    @Test
    void twoParallelStepUpsWithOneRecoveryCodeSucceedOnce() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        String code = enrolled(caller).recoveryCodes().get(0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return stepUp(caller, PASSWORD, null, code)
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(204, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stepUpRows(caller)).hasSize(1);
        assertThat(unusedRecoveryCodes(caller)).isEqualTo(9);
    }

    // ---------------------------------------------------------------------------------------
    // Session binding and freshness
    // ---------------------------------------------------------------------------------------

    @Test
    void aStepUpAuthorizesOnlyTheSessionThatMadeIt() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller first = caller(org, "EMPLOYEE");
        Caller second = anotherSession(first);
        enrolled(first);
        assertThat(
                        stepUp(first, PASSWORD, currentCode(secretOf(first)), null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);

        MvcResult fromSecond = call(second, REGENERATE);
        MvcResult fromFirst = call(first, REGENERATE);

        assertThat(fromSecond.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(fromSecond))
                .containsEntry("type", "urn:peoplehub:problem:step-up-required");
        assertThat(fromFirst.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aStepUpRowOfAnotherEmployeeOrOrganizationNeverCounts() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        enrolled(caller);
        Caller elsewhere = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        Caller colleague = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        // Rows naming the caller's session id, but someone else's account.
        for (Caller other : List.of(elsewhere, colleague)) {
            jdbc.update(
                    "INSERT INTO session_step_up (organization_id, employee_id, session_id, method)"
                            + " VALUES (?, ?, ?, 'PASSWORD_AND_TOTP')",
                    other.employee().organizationId(),
                    other.employee().id(),
                    caller.session());
        }

        assertThat(call(caller, REGENERATE).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void aStepUpIsFreshForFiveMinutes() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        enrolled(caller);
        assertThat(
                        stepUp(caller, PASSWORD, currentCode(secretOf(caller)), null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);

        backdateStepUps(caller, Duration.ofMinutes(4).plusSeconds(50));
        assertThat(call(caller, REGENERATE).getResponse().getStatus()).isEqualTo(200);

        backdateStepUps(caller, Duration.ofMinutes(5).plusSeconds(1));
        MvcResult stale = call(caller, REGENERATE);
        assertThat(stale.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(stale)).containsEntry("type", "urn:peoplehub:problem:step-up-required");
    }

    @Test
    void aPasswordOnlyStepUpDoesNotCountOnceMfaIsEnabled() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        assertThat(stepUp(caller, PASSWORD, null, null).getResponse().getStatus()).isEqualTo(204);
        enrolled(caller);

        MvcResult result = call(caller, REGENERATE);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result)).containsEntry("type", "urn:peoplehub:problem:step-up-required");
    }

    // ---------------------------------------------------------------------------------------
    // Recovery-code regeneration
    // ---------------------------------------------------------------------------------------

    @Test
    void regeneratingReplacesTheUnusedCodesWithoutDeletingAnyRow() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        Enrollment enrollment = enrolled(caller);
        // One old code already used, for its row to keep used_at.
        assertThat(
                        stepUp(caller, PASSWORD, null, enrollment.recoveryCodes().get(0))
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);

        MvcResult result = call(caller, REGENERATE);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        @SuppressWarnings("unchecked")
        List<String> fresh = (List<String>) body(result).get("recoveryCodes");
        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(enrollment.recoveryCodes());
        assertThat(
                        jdbc.queryForMap(
                                "SELECT count(*) AS total,"
                                        + " count(*) FILTER (WHERE used_at IS NOT NULL) AS used,"
                                        + " count(*) FILTER (WHERE invalidated_at IS NOT NULL)"
                                        + " AS invalidated,"
                                        + " count(*) FILTER (WHERE used_at IS NULL"
                                        + " AND invalidated_at IS NULL) AS usable"
                                        + " FROM mfa_recovery_code WHERE employee_id = ?",
                                caller.employee().id()))
                .containsEntry("total", 20L)
                .containsEntry("used", 1L)
                .containsEntry("invalidated", 9L)
                .containsEntry("usable", 10L);
        Map<String, Object> audit = lastAudit(caller, "MFA_RECOVERY_CODES_REGENERATED");
        assertThat(attributes(audit)).containsEntry("codesIssued", 10);
        for (String code : fresh) {
            assertThat(audit.get("details").toString()).doesNotContain(code);
        }

        // An old code no longer steps up; a new one does.
        clock.advance(Duration.ofSeconds(30));
        assertThat(
                        stepUp(caller, PASSWORD, null, enrollment.recoveryCodes().get(1))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(stepUp(caller, PASSWORD, null, fresh.get(0)).getResponse().getStatus())
                .isEqualTo(204);
    }

    @Test
    void regeneratingNeedsMfaAndAStepUp() throws Exception {
        Caller notEnrolled = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        MvcResult none = call(notEnrolled, REGENERATE);
        assertThat(none.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(none)).containsEntry("detail", MfaSelfService.NOT_ENABLED);

        Caller enrolled = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        enrolled(enrolled);
        MvcResult noStepUp = call(enrolled, REGENERATE);
        assertThat(noStepUp.getResponse().getStatus()).isEqualTo(403);
        assertThat(unusedRecoveryCodes(enrolled)).isEqualTo(10);
        assertThat(auditCount(enrolled, "MFA_RECOVERY_CODES_REGENERATED")).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Disabling MFA
    // ---------------------------------------------------------------------------------------

    @Test
    void disablingAfterAStepUpTurnsMfaOffCompletely() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller caller = caller(org, "EMPLOYEE");
        byte[] secret = enrolled(caller).secret();
        // An open sign-in challenge from another device.
        String openChallenge = challengeToken(login(caller.employee()));
        assertThat(stepUp(caller, PASSWORD, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(204);

        MvcResult result = call(caller, DISABLE);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT mfa_enabled, mfa_totp_secret, mfa_totp_pending_secret,"
                                        + " mfa_enrolled_at FROM employee WHERE id = ?",
                                caller.employee().id()))
                .containsEntry("mfa_enabled", false)
                .containsEntry("mfa_totp_secret", null)
                .containsEntry("mfa_totp_pending_secret", null)
                .containsEntry("mfa_enrolled_at", null);
        assertThat(unusedRecoveryCodes(caller)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT invalidated_at IS NOT NULL FROM mfa_challenge"
                                        + " WHERE token_hash = ?",
                                Boolean.class,
                                secureTokens.hash(openChallenge)))
                .isTrue();
        assertThat(attributes(lastAudit(caller, "MFA_DISABLED")))
                .containsEntry("codesInvalidated", 10);
        // This session stays; the reminder applies again; the password alone signs in now.
        mvc.perform(get("/api/v1/me").header("Authorization", caller.bearer()))
                .andExpect(jsonPath("$.mfa.enabled").value(false))
                .andExpect(jsonPath("$.mfa.showReminder").value(true));
        assertThat(body(login(caller.employee()))).containsKey("accessToken");
    }

    @Test
    void disablingIsRefusedWhileThePolicyRequiresMfa() throws Exception {
        Caller caller = caller(organization(MfaPolicy.REQUIRED_FOR_ALL), "EMPLOYEE");
        byte[] secret = enrolled(caller).secret();
        assertThat(stepUp(caller, PASSWORD, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(204);

        MvcResult result = call(caller, DISABLE);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result)).containsEntry("detail", MfaSelfService.REQUIRED);
        assertThat(mfaEnabled(caller)).isTrue();
        assertThat(unusedRecoveryCodes(caller)).isEqualTo(10);
    }

    @Test
    void disablingNeedsMfaAndAStepUp() throws Exception {
        Caller notEnrolled = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        assertThat(call(notEnrolled, DISABLE).getResponse().getStatus()).isEqualTo(409);

        Caller enrolled = caller(organization(MfaPolicy.DISABLED), "EMPLOYEE");
        enrolled(enrolled);
        MvcResult noStepUp = call(enrolled, DISABLE);
        assertThat(noStepUp.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(noStepUp)).containsEntry("type", "urn:peoplehub:problem:step-up-required");
        assertThat(mfaEnabled(enrolled)).isTrue();

        // Under DISABLED, an existing enrollment can still be turned off (it is not required).
        assertThat(
                        stepUp(enrolled, PASSWORD, currentCode(secretOf(enrolled)), null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        assertThat(call(enrolled, DISABLE).getResponse().getStatus()).isEqualTo(204);
        assertThat(mfaEnabled(enrolled)).isFalse();
    }

    // ---------------------------------------------------------------------------------------
    // Re-enrollment
    // ---------------------------------------------------------------------------------------

    @Test
    void reEnrollingKeepsTheExistingFactorUntilTheNewOneIsConfirmed() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        Enrollment old = enrolled(caller);
        String activeBefore = activeSecret(caller);
        assertThat(
                        stepUp(caller, PASSWORD, currentCode(old.secret()), null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);

        MvcResult started = call(caller, ENROLL);

        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        assertThat(started.getResponse().getHeader("Cache-Control")).contains("no-store");
        String newSecretText = (String) body(started).get("secret");
        byte[] newSecret = Base32.decode(newSecretText).orElseThrow();
        String pending = pendingSecret(caller);
        assertThat(pending)
                .startsWith(TestMfaKeysEnvironment.KEY_ID + ":")
                .doesNotContain(newSecretText);
        assertThat(
                        cipher.decrypt(
                                pending,
                                caller.employee().organizationId(),
                                caller.employee().id()))
                .isEqualTo(newSecret);
        // The active factor is untouched and still works.
        assertThat(activeSecret(caller)).isEqualTo(activeBefore);
        assertThat(mfaEnabled(caller)).isTrue();
        assertThat(unusedRecoveryCodes(caller)).isEqualTo(10);
        clock.advance(Duration.ofSeconds(30));
        assertThat(completeSignIn(caller, currentCode(old.secret()), null)).isEqualTo(200);
        assertThat(completeSignIn(caller, null, old.recoveryCodes().get(0))).isEqualTo(200);
    }

    @Test
    void confirmingAReEnrollmentSwapsTheSecretAndTheRecoveryCodesAtomically() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        Enrollment old = enrolled(caller);
        String oldCode = currentCode(old.secret());
        assertThat(stepUp(caller, PASSWORD, oldCode, null).getResponse().getStatus())
                .isEqualTo(204);
        long usedStep = lastStep(caller);
        byte[] newSecret =
                Base32.decode((String) body(call(caller, ENROLL)).get("secret")).orElseThrow();
        String pending = pendingSecret(caller);
        Instant confirmedAt = clock.instant();

        // The new app's code in the very step the old one was just used in is accepted.
        MvcResult confirmed = confirm(caller, currentCode(newSecret));

        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<String> fresh = (List<String>) body(confirmed).get("recoveryCodes");
        assertThat(fresh).hasSize(10);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT mfa_enabled, mfa_totp_secret, mfa_totp_pending_secret,"
                                + " mfa_enrolled_at, mfa_totp_last_step FROM employee WHERE id = ?",
                        caller.employee().id());
        assertThat(row.get("mfa_enabled")).isEqualTo(true);
        assertThat(row.get("mfa_totp_secret")).isEqualTo(pending);
        assertThat(row.get("mfa_totp_pending_secret")).isNull();
        assertThat(((Timestamp) row.get("mfa_enrolled_at")).toInstant()).isEqualTo(confirmedAt);
        assertThat(row.get("mfa_totp_last_step")).isEqualTo(usedStep); // the later of the two
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM mfa_recovery_code WHERE employee_id = ?"
                                        + " AND invalidated_at IS NOT NULL",
                                Integer.class,
                                caller.employee().id()))
                .isEqualTo(10);
        assertThat(unusedRecoveryCodes(caller)).isEqualTo(10);
        assertThat(attributes(lastAudit(caller, "MFA_ENROLLED")))
                .containsEntry("reenrolled", true)
                .containsEntry("codesIssued", 10);

        // Only the new factor works now, and the code used to confirm is not accepted again.
        assertThat(completeSignIn(caller, currentCode(newSecret), null)).isEqualTo(400);
        clock.advance(Duration.ofSeconds(30));
        assertThat(completeSignIn(caller, currentCode(old.secret()), null)).isEqualTo(400);
        assertThat(completeSignIn(caller, null, old.recoveryCodes().get(1))).isEqualTo(400);
        assertThat(completeSignIn(caller, currentCode(newSecret), null)).isEqualTo(200);
        assertThat(completeSignIn(caller, null, fresh.get(0))).isEqualTo(200);
    }

    @Test
    void restartingReplacesOnlyThePendingSecretAndAbandoningChangesNothing() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        Enrollment old = enrolled(caller);
        String activeBefore = activeSecret(caller);
        assertThat(
                        stepUp(caller, PASSWORD, currentCode(old.secret()), null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        byte[] first =
                Base32.decode((String) body(call(caller, ENROLL)).get("secret")).orElseThrow();
        byte[] second =
                Base32.decode((String) body(call(caller, ENROLL)).get("secret")).orElseThrow();

        assertThat(confirm(caller, currentCode(first)).getResponse().getStatus()).isEqualTo(400);
        assertThat(activeSecret(caller)).isEqualTo(activeBefore);
        assertThat(
                        cipher.decrypt(
                                pendingSecret(caller),
                                caller.employee().organizationId(),
                                caller.employee().id()))
                .isEqualTo(second);
        assertThat(unusedRecoveryCodes(caller)).isEqualTo(10);
        assertThat(auditCount(caller, "MFA_ENROLLED")).isZero();
        // Abandoned: the old factor keeps working as before.
        clock.advance(Duration.ofSeconds(30));
        assertThat(completeSignIn(caller, currentCode(old.secret()), null)).isEqualTo(200);
    }

    @Test
    void reEnrollingNeedsAFreshStepUpOfThisSession() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        enrolled(caller);
        Caller otherSession = anotherSession(caller);
        assertThat(
                        stepUp(otherSession, PASSWORD, currentCode(secretOf(caller)), null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);

        MvcResult result = call(caller, ENROLL);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result)).containsEntry("type", "urn:peoplehub:problem:step-up-required");
        assertThat(pendingSecret(caller)).isNull();
    }

    @Test
    void reEnrollingIsNotOfferedWhileMfaIsDisabledForTheOrganization() throws Exception {
        Caller caller = caller(organization(MfaPolicy.DISABLED), "EMPLOYEE");
        byte[] secret = enrolled(caller).secret();
        assertThat(stepUp(caller, PASSWORD, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(204);

        MvcResult result = call(caller, ENROLL);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(pendingSecret(caller)).isNull();
    }

    @Test
    void thePendingSecretIsBoundToItsAccount() throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        Caller colleague = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(caller).secret();
        stepUp(caller, PASSWORD, currentCode(secret), null);
        call(caller, ENROLL);
        String pending = pendingSecret(caller);

        assertThatThrownBy(
                        () ->
                                cipher.decrypt(
                                        pending,
                                        colleague.employee().organizationId(),
                                        colleague.employee().id()))
                .isInstanceOf(MfaSecretUnreadableException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Secrecy and documentation
    // ---------------------------------------------------------------------------------------

    @Test
    void noPasswordSecretOrCodeReachesTheLogs(CapturedOutput output) throws Exception {
        Caller caller = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        Enrollment enrollment = enrolled(caller);
        String code = currentCode(enrollment.secret());
        stepUp(caller, "wrong-password-123", null, null);
        stepUp(caller, PASSWORD, code, null);
        @SuppressWarnings("unchecked")
        List<String> regenerated =
                (List<String>) body(call(caller, REGENERATE)).get("recoveryCodes");
        String newSecret = (String) body(call(caller, ENROLL)).get("secret");

        List<String> forbidden = new ArrayList<>();
        forbidden.add(PASSWORD);
        forbidden.add("wrong-password-123");
        forbidden.add(newSecret);
        forbidden.add(Base32.encode(enrollment.secret()));
        forbidden.add("otpauth://");
        forbidden.add("\"" + code + "\"");
        forbidden.addAll(enrollment.recoveryCodes());
        forbidden.addAll(regenerated);
        String logs = output.getAll();
        for (String value : forbidden) {
            assertThat(logs).doesNotContain(value);
        }
    }

    @Test
    void theOpenApiDocumentDescribesTheStepUpEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/me/step-up'].post.security").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/me/step-up'].post.responses['400']").exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/me/mfa/disable'].post.responses['409']")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/me/mfa/recovery-codes/regenerate'].post.responses['403']")
                                .exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/me/mfa/enroll'].post.responses['403']")
                                .exists());
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private record Caller(TestIdentities.Employee employee, UUID session, String bearer) {}

    private record Enrollment(byte[] secret, List<String> recoveryCodes) {}

    private TestIdentities.Organization organization(MfaPolicy policy) {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        jdbc.update("UPDATE organization SET mfa_policy = ? WHERE id = ?", policy.name(), org.id());
        return org;
    }

    private Caller caller(TestIdentities.Organization org, String role) {
        return session(TestIdentities.activeEmployee(jdbc, org, role, passwordHash));
    }

    private Caller anotherSession(Caller caller) {
        return session(caller.employee());
    }

    private Caller session(TestIdentities.Employee employee) {
        UUID session = TestIdentities.activeSession(jdbc, employee, clock.instant());
        String token =
                issuer.issue(employee.id(), employee.organizationId(), employee.role(), session)
                        .value();
        return new Caller(employee, session, "Bearer " + token);
    }

    private Enrollment enrolled(Caller caller) {
        TestIdentities.Employee employee = caller.employee();
        byte[] secret = Totp.newSecret();
        jdbc.update(
                "UPDATE employee SET mfa_enabled = true, mfa_totp_secret = ?, mfa_enrolled_at = ?"
                        + " WHERE id = ?",
                cipher.encrypt(secret, employee.organizationId(), employee.id()),
                Timestamp.from(clock.instant()),
                employee.id());
        List<String> codes = recoveryCodes.generate();
        for (String code : codes) {
            jdbc.update(
                    "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash)"
                            + " VALUES (?, ?, ?)",
                    employee.organizationId(),
                    employee.id(),
                    secureTokens.hash(code.replace("-", "")));
        }
        return new Enrollment(secret, codes);
    }

    private byte[] secretOf(Caller caller) {
        return cipher.decrypt(
                activeSecret(caller), caller.employee().organizationId(), caller.employee().id());
    }

    private MvcResult stepUp(Caller caller, String password, String code, String recoveryCode)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("password", password);
        if (code != null) {
            request.put("code", code);
        }
        if (recoveryCode != null) {
            request.put("recoveryCode", recoveryCode);
        }
        return mvc.perform(
                        post(STEP_UP)
                                .header("Authorization", caller.bearer())
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(request)))
                .andReturn();
    }

    private MvcResult call(Caller caller, String path) throws Exception {
        return mvc.perform(post(path).header("Authorization", caller.bearer())).andReturn();
    }

    private MvcResult confirm(Caller caller, String code) throws Exception {
        return mvc.perform(
                        post(CONFIRM)
                                .header("Authorization", caller.bearer())
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(Map.of("code", code))))
                .andReturn();
    }

    private MvcResult login(TestIdentities.Employee employee) throws Exception {
        return mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "organization",
                                                        employee.organizationLoginKey(),
                                                        "email",
                                                        employee.email(),
                                                        "password",
                                                        PASSWORD))))
                .andReturn();
    }

    private static String challengeToken(MvcResult loginResult) throws Exception {
        assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
        return (String) body(loginResult).get("challengeToken");
    }

    /** Signs in through the password step and the MFA challenge; returns the challenge status. */
    private int completeSignIn(Caller caller, String code, String recoveryCode) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("challengeToken", challengeToken(login(caller.employee())));
        if (code != null) {
            request.put("code", code);
        }
        if (recoveryCode != null) {
            request.put("recoveryCode", recoveryCode);
        }
        return mvc.perform(
                        post("/api/v1/auth/mfa/challenge")
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String currentCode(byte[] secret) {
        return Totp.code(secret, Totp.step(clock.instant()), 6);
    }

    private String wrongCode(byte[] secret) {
        long step = Totp.step(clock.instant());
        List<String> valid =
                List.of(
                        Totp.code(secret, step - 1, 6),
                        Totp.code(secret, step, 6),
                        Totp.code(secret, step + 1, 6));
        for (int i = 0; ; i++) {
            String candidate = String.format("%06d", i);
            if (!valid.contains(candidate)) {
                return candidate;
            }
        }
    }

    private void backdateStepUps(Caller caller, Duration age) {
        jdbc.update(
                "UPDATE session_step_up SET verified_at = now() - make_interval(secs => ?)"
                        + " WHERE employee_id = ?",
                (double) age.toSeconds(),
                caller.employee().id());
    }

    private List<Map<String, Object>> stepUpRows(Caller caller) {
        return jdbc.queryForList(
                "SELECT organization_id, session_id, method FROM session_step_up"
                        + " WHERE employee_id = ?",
                caller.employee().id());
    }

    private String activeSecret(Caller caller) {
        return jdbc.queryForObject(
                "SELECT mfa_totp_secret FROM employee WHERE id = ?",
                String.class,
                caller.employee().id());
    }

    private String pendingSecret(Caller caller) {
        return jdbc.queryForObject(
                "SELECT mfa_totp_pending_secret FROM employee WHERE id = ?",
                String.class,
                caller.employee().id());
    }

    private boolean mfaEnabled(Caller caller) {
        return jdbc.queryForObject(
                "SELECT mfa_enabled FROM employee WHERE id = ?",
                Boolean.class,
                caller.employee().id());
    }

    private long lastStep(Caller caller) {
        return jdbc.queryForObject(
                "SELECT mfa_totp_last_step FROM employee WHERE id = ?",
                Long.class,
                caller.employee().id());
    }

    private int failedLoginCount(Caller caller) {
        return jdbc.queryForObject(
                "SELECT failed_login_count FROM employee WHERE id = ?",
                Integer.class,
                caller.employee().id());
    }

    private int unusedRecoveryCodes(Caller caller) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM mfa_recovery_code WHERE employee_id = ?"
                        + " AND used_at IS NULL AND invalidated_at IS NULL",
                Integer.class,
                caller.employee().id());
    }

    private int auditCount(Caller caller, String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE target_id = ? AND action = ?",
                Integer.class,
                caller.employee().id().toString(),
                action);
    }

    private Map<String, Object> lastAudit(Caller caller, String action) {
        return jdbc.queryForMap(
                "SELECT actor_id, details::text AS details FROM audit_log"
                        + " WHERE target_id = ? AND action = ? ORDER BY id DESC LIMIT 1",
                caller.employee().id().toString(),
                action);
    }

    private static Map<String, Object> attributes(Map<String, Object> auditRow) throws Exception {
        Map<String, Object> details =
                JSON.readValue((String) auditRow.get("details"), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) details.get("attributes");
        return attributes;
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }
}
