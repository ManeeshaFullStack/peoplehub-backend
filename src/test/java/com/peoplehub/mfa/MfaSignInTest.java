package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.security.SecureTokens;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.TestIdentities;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MFA step of a sign-in end to end (b2-7, B2-7/2, B2-7/9, B2-7/10, B2-7/11, B2-7/22, B2-7/23,
 * B2-7/24, B2-7/25; Spec 8.3): which password step leads to a challenge, an enrollment or a
 * session; completing a challenge with a TOTP or recovery code; the required enrollment at sign-in;
 * wrong codes, the challenge limit and the per-account lockout; single use, expiry and replay;
 * tenant isolation; audit rows; and no secret in the logs. Runs on a controllable clock so TOTP
 * steps and expiry are exact.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "peoplehub.security.app-origin=" + MfaSignInTest.APP_ORIGIN)
class MfaSignInTest {

    static final String APP_ORIGIN = "https://app.peoplehub.test";

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String CHALLENGE = "/api/v1/auth/mfa/challenge";
    private static final String ENROLL = "/api/v1/auth/mfa/enroll";
    private static final String ENROLL_CONFIRM = "/api/v1/auth/mfa/enroll/confirm";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
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
    // Which password step leads where
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}, {1}, selected={2}")
    @CsvSource({
        "DISABLED, SUPER_ADMIN, true",
        "OPTIONAL, ADMIN, false",
        "REQUIRED_FOR_ADMINS, EMPLOYEE, false",
        "REQUIRED_FOR_SELECTED_USERS, ADMIN, false"
    })
    void withoutEnrollmentOrRequirementThePasswordAloneSignsIn(
            MfaPolicy policy, String role, boolean selected) throws Exception {
        TestIdentities.Employee employee = employee(organization(policy), role);
        jdbc.update("UPDATE employee SET mfa_required = ? WHERE id = ?", selected, employee.id());

        MvcResult result = login(employee);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result))
                .containsOnlyKeys("accessToken", "tokenType", "expiresIn", "csrfToken");
        assertThat(result.getResponse().getCookie("__Secure-peoplehub_rt")).isNotNull();
        assertThat(challengeCount(employee)).isZero();
        assertThat(auditCount(employee, "LOGIN_SUCCEEDED")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(MfaPolicy.class)
    void anEnrolledPersonIsAlwaysChallengedWhateverThePolicy(MfaPolicy policy) throws Exception {
        TestIdentities.Employee employee = employee(organization(policy), "EMPLOYEE");
        enrolled(employee);

        MvcResult result = login(employee);

        assertMfaStep(result, "CHALLENGE", employee);
    }

    @ParameterizedTest(name = "{0}, {1}, selected={2}")
    @CsvSource({
        "REQUIRED_FOR_ADMINS, ADMIN, false",
        "REQUIRED_FOR_ADMINS, SUPER_ADMIN, false",
        "REQUIRED_FOR_SELECTED_USERS, EMPLOYEE, true",
        "REQUIRED_FOR_ALL, EMPLOYEE, false"
    })
    void aRequiredPersonWhoHasNotEnrolledMustEnrollFirst(
            MfaPolicy policy, String role, boolean selected) throws Exception {
        TestIdentities.Employee employee = employee(organization(policy), role);
        jdbc.update("UPDATE employee SET mfa_required = ? WHERE id = ?", selected, employee.id());

        MvcResult result = login(employee);

        assertMfaStep(result, "ENROLL", employee);
    }

    @Test
    void theChallengeIsStoredHashedWithTheDeviceAndAddressOfThePasswordStep() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        enrolled(employee);

        String token = challengeToken(login(employee, "10.1.2.3"));

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT organization_id, token_hash, purpose, device_label, host(ip) AS ip,"
                                + " expires_at, failed_attempts, consumed_at, invalidated_at"
                                + " FROM mfa_challenge WHERE employee_id = ?",
                        employee.id());
        assertThat(row.get("organization_id")).isEqualTo(employee.organizationId());
        assertThat(row.get("token_hash")).isEqualTo(secureTokens.hash(token)).isNotEqualTo(token);
        assertThat(row.get("purpose")).isEqualTo("CHALLENGE");
        assertThat(row.get("device_label")).isEqualTo("Chrome on Windows");
        assertThat(row.get("ip")).isEqualTo("10.1.2.3");
        assertThat(((Timestamp) row.get("expires_at")).toInstant())
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(row.get("failed_attempts")).isEqualTo(0);
        assertThat(row.get("consumed_at")).isNull();
        assertThat(row.get("invalidated_at")).isNull();
        // The password step is recorded as the password step.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT success FROM login_attempt WHERE email_attempted = ?",
                                Boolean.class,
                                employee.email()))
                .isTrue();
    }

    @Test
    void aWrongPasswordIsTheSameGeneric401WhetherOrNotMfaFollows() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        enrolled(employee);
        TestIdentities.Employee plain = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");

        MvcResult enrolledWrong = login(employee, "10.0.0.1", "wrong-password-123");
        MvcResult plainWrong = login(plain, "10.0.0.1", "wrong-password-123");

        assertThat(enrolledWrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(plainWrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(enrolledWrong).get("detail")).isEqualTo(body(plainWrong).get("detail"));
        assertThat(challengeCount(employee)).isZero();
    }

    @Test
    void aLockedAccountGetsNoChallengeEvenWithTheRightPassword() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        enrolled(employee);
        jdbc.update(
                "UPDATE employee SET locked_until = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(1))),
                employee.id());

        assertThat(login(employee).getResponse().getStatus()).isEqualTo(401);
        assertThat(challengeCount(employee)).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Completing a challenge
    // ---------------------------------------------------------------------------------------

    @Test
    void aTotpCodeCompletesTheSignIn() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        String token = challengeToken(login(employee));
        assertThat(auditCount(employee, "LOGIN_SUCCEEDED")).isZero();

        MvcResult result = challenge(token, currentCode(secret), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        Map<String, Object> body = body(result);
        assertThat(body).containsOnlyKeys("accessToken", "tokenType", "expiresIn", "csrfToken");
        assertThat(result.getResponse().getCookie("__Secure-peoplehub_rt")).isNotNull();
        String accessToken = (String) body.get("accessToken");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.id").value(employee.id().toString()))
                .andExpect(jsonPath("$.mfa.enabled").value(true));

        assertThat(challengeState(employee)).containsEntry("consumed", true);
        assertThat(lastStep(employee)).isEqualTo(Totp.step(clock.instant()));
        Map<String, Object> audit = lastAudit(employee, "LOGIN_SUCCEEDED");
        assertThat(audit.get("actor_id")).isEqualTo(employee.id().toString());
        assertThat(attributes(audit).get("mfaMethod")).isEqualTo("TOTP");
    }

    @Test
    void theSessionKeepsTheDeviceOfThePasswordStepAndAnotherAddressIsNotRefused() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        String token = challengeToken(login(employee, "10.1.1.1"));

        MvcResult result =
                mvc.perform(
                                post(CHALLENGE)
                                        .with(
                                                r -> {
                                                    r.setRemoteAddr("192.0.2.77");
                                                    return r;
                                                })
                                        .header("User-Agent", "curl/8.0")
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "challengeToken",
                                                                token,
                                                                "code",
                                                                currentCode(secret)))))
                        .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT device_label FROM refresh_token WHERE employee_id = ?",
                                String.class,
                                employee.id()))
                .isEqualTo("Chrome on Windows");
    }

    @Test
    void aTotpCodeIsNeverAcceptedTwice() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        String code = currentCode(secret);
        assertThat(challenge(challengeToken(login(employee)), code, null).getResponse().getStatus())
                .isEqualTo(200);

        // A second sign-in in the same 30-second step: the same code is a replay.
        MvcResult replay = challenge(challengeToken(login(employee)), code, null);
        assertThat(replay.getResponse().getStatus()).isEqualTo(400);

        // So is a code from the step before the one already used.
        long used = lastStep(employee);
        clock.advance(Duration.ofSeconds(30));
        MvcResult older =
                challenge(challengeToken(login(employee)), Totp.code(secret, used, 6), null);
        assertThat(older.getResponse().getStatus()).isEqualTo(400);
        // The next step's code works.
        assertThat(
                        challenge(challengeToken(login(employee)), currentCode(secret), null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aRecoveryCodeSignsInOnceAndIsAudited() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        List<String> codes = enrolled(employee).recoveryCodes();
        // Typed in lower case without dashes: still the same code.
        String typed = codes.get(3).replace("-", "").toLowerCase(Locale.ROOT);

        MvcResult result = challenge(challengeToken(login(employee)), null, typed);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT used_at FROM mfa_recovery_code WHERE code_hash = ?",
                                Timestamp.class,
                                secureTokens.hash(codes.get(3).replace("-", ""))))
                .isNotNull();
        Map<String, Object> used = lastAudit(employee, "MFA_RECOVERY_CODE_USED");
        assertThat(attributes(used).get("codesLeft")).isEqualTo(9);
        assertThat(used.get("details").toString())
                .doesNotContain(codes.get(3))
                .doesNotContain(typed);
        assertThat(attributes(lastAudit(employee, "LOGIN_SUCCEEDED")).get("mfaMethod"))
                .isEqualTo("RECOVERY_CODE");

        // The same code a second time is wrong.
        MvcResult again = challenge(challengeToken(login(employee)), null, codes.get(3));
        assertThat(again.getResponse().getStatus()).isEqualTo(400);
        assertThat(again.getResponse().getContentAsString()).contains("\"field\":\"recoveryCode\"");
        // Another unused code still works.
        assertThat(
                        challenge(challengeToken(login(employee)), null, codes.get(4))
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    @Test
    void anInvalidatedRecoveryCodeDoesNotWork() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        List<String> codes = enrolled(employee).recoveryCodes();
        jdbc.update(
                "UPDATE mfa_recovery_code SET invalidated_at = now() WHERE code_hash = ?",
                secureTokens.hash(codes.get(0).replace("-", "")));

        assertThat(
                        challenge(challengeToken(login(employee)), null, codes.get(0))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }

    @Test
    void exactlyOneOfCodeAndRecoveryCodeIsRequired() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        Enrollment enrollment = enrolled(employee);
        String token = challengeToken(login(employee));

        MvcResult neither = challenge(token, null, null);
        MvcResult both =
                challenge(
                        token, currentCode(enrollment.secret()), enrollment.recoveryCodes().get(0));

        assertThat(neither.getResponse().getStatus()).isEqualTo(400);
        assertThat(both.getResponse().getStatus()).isEqualTo(400);
        // Neither counted as a wrong code.
        assertThat(challengeState(employee)).containsEntry("failed_attempts", 0);
        assertThat(
                        challenge(token, currentCode(enrollment.secret()), null)
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------
    // Wrong codes, limits and lockout
    // ---------------------------------------------------------------------------------------

    @Test
    void wrongCodesCountTowardTheChallengeLimitAndTheAccountLockout() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        String token = challengeToken(login(employee));
        String wrong = wrongCode(secret);

        for (int attempt = 1; attempt < MfaChallenges.MAX_FAILED_ATTEMPTS; attempt++) {
            MvcResult result = challenge(token, wrong, null);
            assertThat(result.getResponse().getStatus()).as("attempt %d", attempt).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"field\":\"code\"")
                    .doesNotContain(wrong);
            assertThat(failedLoginCount(employee)).isEqualTo(attempt);
            assertThat(challengeState(employee)).containsEntry("failed_attempts", attempt);
        }
        // The fifth wrong code ends the challenge and, at the lockout threshold, locks the account.
        MvcResult last = challenge(token, wrong, null);
        assertThat(last.getResponse().getStatus()).isEqualTo(401);
        assertThat(challengeState(employee)).containsEntry("invalidated", true);
        assertThat(auditCount(employee, "ACCOUNT_LOCKED")).isEqualTo(1);

        // Neither the right code on the dead challenge, nor the right password, signs in now.
        assertThat(challenge(token, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(login(employee).getResponse().getStatus()).isEqualTo(401);
        assertThat(auditCount(employee, "LOGIN_SUCCEEDED")).isZero();
    }

    @Test
    void aChallengeOfAnAccountLockedMeanwhileCannotBeCompleted() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        String token = challengeToken(login(employee));
        jdbc.update(
                "UPDATE employee SET locked_until = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(1))),
                employee.id());

        assertThat(challenge(token, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(challengeState(employee)).containsEntry("invalidated", true);
        assertThat(sessionCount(employee)).isZero();
    }

    @Test
    void theFailedSignInCountClearsOnlyWhenTheMfaStepCompletes() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        login(employee, "10.0.0.1", "wrong-password-123");
        login(employee, "10.0.0.1", "wrong-password-123");
        assertThat(failedLoginCount(employee)).isEqualTo(2);

        String token = challengeToken(login(employee));
        assertThat(failedLoginCount(employee)).isEqualTo(2); // the password step alone: kept
        challenge(token, wrongCode(secret), null);
        assertThat(failedLoginCount(employee)).isEqualTo(3);

        assertThat(challenge(token, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(failedLoginCount(employee)).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Single use, expiry and the account's state
    // ---------------------------------------------------------------------------------------

    @Test
    void aChallengeWorksOnce() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        String token = challengeToken(login(employee));
        assertThat(challenge(token, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(200);
        clock.advance(Duration.ofSeconds(30));

        MvcResult again = challenge(token, currentCode(secret), null);

        assertThat(again.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(again))
                .containsEntry("type", "urn:peoplehub:problem:unauthorized")
                .containsEntry(
                        "detail", "Your sign-in could not be completed. Please sign in again.");
        assertThat(sessionCount(employee)).isEqualTo(1);
    }

    @Test
    void twoParallelCompletionsOfOneChallengeOpenOneSession() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        String token = challengeToken(login(employee));
        String code = currentCode(secret);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return challenge(token, code, null).getResponse().getStatus();
                                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (java.util.concurrent.Future<Integer> result : results) {
                statuses.add(result.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sessionCount(employee)).isEqualTo(1);
        assertThat(auditCount(employee, "LOGIN_SUCCEEDED")).isEqualTo(1);
    }

    @Test
    void aChallengeExpiresAfterFiveMinutes() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        String token = challengeToken(login(employee));

        clock.advance(Duration.ofMinutes(5));

        assertThat(challenge(token, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(sessionCount(employee)).isZero();
        // Expiry is not a wrong code.
        assertThat(failedLoginCount(employee)).isZero();
    }

    @Test
    void unknownOrMismatchedTokensAreTheSame401() throws Exception {
        TestIdentities.Employee enrolledOne =
                employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(enrolledOne).secret();
        TestIdentities.Employee required =
                employee(organization(MfaPolicy.REQUIRED_FOR_ALL), "EMPLOYEE");
        String enrollToken = challengeToken(login(required));
        String challengeToken = challengeToken(login(enrolledOne));

        List<MvcResult> results =
                List.of(
                        challenge(secureTokens.generateRaw(), currentCode(secret), null),
                        // An ENROLL token cannot complete a CHALLENGE, and the other way round.
                        challenge(enrollToken, currentCode(secret), null),
                        enrollStart(challengeToken),
                        enrollConfirm(challengeToken, currentCode(secret)));
        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(result).get("detail"))
                    .isEqualTo("Your sign-in could not be completed. Please sign in again.");
        }
        // The challenge itself is untouched by being presented at the wrong endpoint.
        assertThat(challenge(challengeToken, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void aDeactivationBetweenPasswordAndCodeEndsTheChallenge() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        String token = challengeToken(login(employee));
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", employee.id());

        assertThat(challenge(token, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(challengeState(employee)).containsEntry("invalidated", true);
        assertThat(sessionCount(employee)).isZero();
    }

    @Test
    void anMfaResetMeanwhileEndsTheChallenge() throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        byte[] secret = enrolled(employee).secret();
        String token = challengeToken(login(employee));
        jdbc.update(
                "UPDATE employee SET mfa_enabled = false, mfa_enrolled_at = NULL,"
                        + " mfa_totp_secret = NULL WHERE id = ?",
                employee.id());

        assertThat(challenge(token, currentCode(secret), null).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(sessionCount(employee)).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Required enrollment at sign-in
    // ---------------------------------------------------------------------------------------

    @Test
    void aRequiredPersonEnrollsAndIsThenSignedIn() throws Exception {
        TestIdentities.Employee admin =
                employee(organization(MfaPolicy.REQUIRED_FOR_ADMINS), "ADMIN");
        String token = challengeToken(login(admin));
        assertThat(sessionCount(admin)).isZero();

        MvcResult started = enrollStart(token);
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        assertThat(started.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(body(started)).containsOnlyKeys("secret", "otpauthUri");
        byte[] secret = Base32.decode((String) body(started).get("secret")).orElseThrow();
        assertThat(mfaEnabled(admin)).isFalse();

        MvcResult confirmed = enrollConfirm(token, currentCode(secret));

        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertThat(confirmed.getResponse().getHeader("Cache-Control")).contains("no-store");
        Map<String, Object> body = body(confirmed);
        assertThat(body)
                .containsOnlyKeys(
                        "accessToken", "tokenType", "expiresIn", "csrfToken", "recoveryCodes");
        assertThat((List<?>) body.get("recoveryCodes")).hasSize(10);
        assertThat(confirmed.getResponse().getCookie("__Secure-peoplehub_rt")).isNotNull();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + body.get("accessToken")))
                .andExpect(jsonPath("$.mfa.enabled").value(true))
                .andExpect(jsonPath("$.mfa.required").value(true));
        assertThat(mfaEnabled(admin)).isTrue();
        assertThat(challengeState(admin)).containsEntry("consumed", true);
        assertThat(auditCount(admin, "MFA_ENROLLED")).isEqualTo(1);
        assertThat(attributes(lastAudit(admin, "LOGIN_SUCCEEDED")).get("mfaMethod"))
                .isEqualTo("ENROLLED");
        assertThat(lastAudit(admin, "MFA_ENROLLED").get("actor_id"))
                .isEqualTo(admin.id().toString());

        // From now on the password step leads to a challenge.
        clock.advance(Duration.ofSeconds(30));
        MvcResult next = login(admin);
        assertThat(next.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(next)).containsEntry("mfaRequired", "CHALLENGE");
        assertThat(next.getResponse().getCookies()).isEmpty();
        assertThat(sessionCount(admin)).isEqualTo(1); // only the one the enrollment opened
    }

    @Test
    void aWrongEnrollmentCodeCountsLikeAnyWrongMfaCode() throws Exception {
        TestIdentities.Employee employee =
                employee(organization(MfaPolicy.REQUIRED_FOR_ALL), "EMPLOYEE");
        String token = challengeToken(login(employee));
        byte[] secret =
                Base32.decode((String) body(enrollStart(token)).get("secret")).orElseThrow();

        MvcResult wrong = enrollConfirm(token, wrongCode(secret));

        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(failedLoginCount(employee)).isEqualTo(1);
        assertThat(challengeState(employee)).containsEntry("failed_attempts", 1);
        assertThat(mfaEnabled(employee)).isFalse();
        assertThat(enrollConfirm(token, currentCode(secret)).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(failedLoginCount(employee)).isZero();
    }

    @Test
    void anAbandonedEnrollmentLeavesNoSessionAndExpiresAfterTenMinutes() throws Exception {
        TestIdentities.Employee employee =
                employee(organization(MfaPolicy.REQUIRED_FOR_ALL), "EMPLOYEE");
        String token = challengeToken(login(employee));
        byte[] secret =
                Base32.decode((String) body(enrollStart(token)).get("secret")).orElseThrow();

        clock.advance(Duration.ofMinutes(9));
        assertThat(enrollStart(token).getResponse().getStatus()).isEqualTo(200); // still open
        clock.advance(Duration.ofMinutes(1));

        assertThat(enrollConfirm(token, currentCode(secret)).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mfaEnabled(employee)).isFalse();
        assertThat(sessionCount(employee)).isZero();
        // The next sign-in starts again.
        assertMfaStep(login(employee), "ENROLL", employee);
    }

    @Test
    void anEnrollmentConfirmCodeFromAnEarlierSecretIsWrong() throws Exception {
        TestIdentities.Employee employee =
                employee(organization(MfaPolicy.REQUIRED_FOR_ALL), "EMPLOYEE");
        String token = challengeToken(login(employee));
        byte[] first = Base32.decode((String) body(enrollStart(token)).get("secret")).orElseThrow();
        byte[] second =
                Base32.decode((String) body(enrollStart(token)).get("secret")).orElseThrow();

        assertThat(enrollConfirm(token, currentCode(first)).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(enrollConfirm(token, currentCode(second)).getResponse().getStatus())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------
    // Tenant isolation, Origin and secrecy
    // ---------------------------------------------------------------------------------------

    @Test
    void theSameEmailInAnotherOrganizationNeverCompletesThisOrganizationsChallenge()
            throws Exception {
        TestIdentities.Organization a = organization(MfaPolicy.OPTIONAL);
        TestIdentities.Organization b = organization(MfaPolicy.OPTIONAL);
        TestIdentities.Employee inA = employee(a, "EMPLOYEE");
        TestIdentities.Employee inB = sameEmailIn(b, inA.email());
        enrolled(inA);
        byte[] secretOfB = enrolled(inB).secret();

        MvcResult result = challenge(challengeToken(login(inA)), currentCode(secretOfB), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(sessionCount(inA)).isZero();
        assertThat(sessionCount(inB)).isZero();
        assertThat(failedLoginCount(inB)).isZero();
        assertThat(failedLoginCount(inA)).isEqualTo(1);
    }

    @Test
    void aForeignOriginIsRefusedOnEveryMfaStep() throws Exception {
        for (String path : List.of(CHALLENGE, ENROLL, ENROLL_CONFIRM)) {
            MvcResult result =
                    mvc.perform(
                                    post(path)
                                            .header("Origin", "https://evil.example")
                                            .contentType("application/json")
                                            .content(
                                                    JSON.writeValueAsString(
                                                            Map.of(
                                                                    "challengeToken", "x",
                                                                    "code", "123456"))))
                            .andReturn();
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(403);
        }
    }

    @Test
    void noTokenHashSecretOrCodeReachesTheLogs(CapturedOutput output) throws Exception {
        TestIdentities.Employee employee = employee(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        Enrollment enrollment = enrolled(employee);
        String token = challengeToken(login(employee));
        String code = currentCode(enrollment.secret());
        challenge(token, wrongCode(enrollment.secret()), null);
        challenge(token, code, null);
        String recoveryToken = challengeToken(login(employee));
        challenge(recoveryToken, null, enrollment.recoveryCodes().get(0));

        TestIdentities.Employee required =
                employee(organization(MfaPolicy.REQUIRED_FOR_ALL), "EMPLOYEE");
        String enrollToken = challengeToken(login(required));
        MvcResult started = enrollStart(enrollToken);
        String secret = (String) body(started).get("secret");
        MvcResult confirmed =
                enrollConfirm(enrollToken, currentCode(Base32.decode(secret).orElseThrow()));
        @SuppressWarnings("unchecked")
        List<String> issued = (List<String>) body(confirmed).get("recoveryCodes");

        List<String> forbidden = new ArrayList<>();
        for (String raw : List.of(token, recoveryToken, enrollToken)) {
            forbidden.add(raw);
            forbidden.add(secureTokens.hash(raw));
        }
        forbidden.add(secret);
        forbidden.add(Base32.encode(enrollment.secret()));
        forbidden.add("otpauth://");
        forbidden.add("\"" + code + "\"");
        forbidden.addAll(enrollment.recoveryCodes());
        forbidden.addAll(issued);
        String logs = output.getAll();
        for (String value : forbidden) {
            assertThat(logs).doesNotContain(value);
        }
    }

    @Test
    void theOpenApiDocumentDescribesTheMfaSteps() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/mfa/challenge'].post.security")
                                .doesNotExist())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/mfa/challenge'].post.responses['401']")
                                .exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/mfa/challenge'].post.responses['400']")
                                .exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/mfa/enroll'].post.security").doesNotExist())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/mfa/enroll/confirm'].post.responses['401']")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/auth/login'].post.responses['200'].content['application/json'].schema.oneOf")
                                .isArray())
                .andExpect(
                        jsonPath("$.components.schemas.MfaChallengeResponse.properties.mfaRequired")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.MfaEnrolledSessionResponse.properties.recoveryCodes")
                                .exists());
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private record Enrollment(byte[] secret, List<String> recoveryCodes) {}

    private void assertMfaStep(MvcResult result, String purpose, TestIdentities.Employee employee)
            throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        Map<String, Object> body = body(result);
        assertThat(body).containsOnlyKeys("mfaRequired", "challengeToken");
        assertThat(body.get("mfaRequired")).isEqualTo(purpose);
        assertThat((String) body.get("challengeToken")).matches("[0-9a-f]{64}");
        // No session of any kind yet.
        assertThat(result.getResponse().getCookies()).isEmpty();
        assertThat(sessionCount(employee)).isZero();
        assertThat(auditCount(employee, "LOGIN_SUCCEEDED")).isZero();
    }

    private TestIdentities.Organization organization(MfaPolicy policy) {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        jdbc.update("UPDATE organization SET mfa_policy = ? WHERE id = ?", policy.name(), org.id());
        return org;
    }

    private TestIdentities.Employee employee(TestIdentities.Organization org, String role) {
        return TestIdentities.activeEmployee(jdbc, org, role, passwordHash);
    }

    private TestIdentities.Employee sameEmailIn(TestIdentities.Organization org, String email) {
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, status, role, join_date, password_hash)"
                                + " VALUES (?, ?, 'Jane Doe', ?, ?, 'ACTIVE', 'EMPLOYEE',"
                                + " DATE '2026-01-05', ?) RETURNING id",
                        UUID.class,
                        org.id(),
                        "E-" + UUID.randomUUID(),
                        email,
                        email,
                        passwordHash);
        return new TestIdentities.Employee(id, org.id(), org.loginKey(), email, "EMPLOYEE");
    }

    /** A confirmed enrollment written directly (the flow itself is MfaEnrollmentTest's job). */
    private Enrollment enrolled(TestIdentities.Employee employee) {
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

    private MvcResult login(TestIdentities.Employee employee) throws Exception {
        return login(employee, "10.0.0.1");
    }

    private MvcResult login(TestIdentities.Employee employee, String ip) throws Exception {
        return login(employee, ip, PASSWORD);
    }

    private MvcResult login(TestIdentities.Employee employee, String ip, String password)
            throws Exception {
        return mvc.perform(
                        post(LOGIN)
                                .with(
                                        r -> {
                                            r.setRemoteAddr(ip);
                                            return r;
                                        })
                                .header(
                                        "User-Agent",
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                                                + " AppleWebKit/537.36 (KHTML, like Gecko)"
                                                + " Chrome/126.0 Safari/537.36")
                                .header("Origin", APP_ORIGIN)
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "organization",
                                                        employee.organizationLoginKey(),
                                                        "email",
                                                        employee.email(),
                                                        "password",
                                                        password))))
                .andReturn();
    }

    private static String challengeToken(MvcResult loginResult) throws Exception {
        assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
        return (String) body(loginResult).get("challengeToken");
    }

    private MvcResult challenge(String token, String code, String recoveryCode) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("challengeToken", token);
        if (code != null) {
            request.put("code", code);
        }
        if (recoveryCode != null) {
            request.put("recoveryCode", recoveryCode);
        }
        return mvc.perform(
                        post(CHALLENGE)
                                .header("Origin", APP_ORIGIN)
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(request)))
                .andReturn();
    }

    private MvcResult enrollStart(String token) throws Exception {
        return mvc.perform(
                        post(ENROLL)
                                .header("Origin", APP_ORIGIN)
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(Map.of("challengeToken", token))))
                .andReturn();
    }

    private MvcResult enrollConfirm(String token, String code) throws Exception {
        return mvc.perform(
                        post(ENROLL_CONFIRM)
                                .header("Origin", APP_ORIGIN)
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of("challengeToken", token, "code", code))))
                .andReturn();
    }

    private String currentCode(byte[] secret) {
        return Totp.code(secret, Totp.step(clock.instant()), 6);
    }

    /** A six-digit code that matches none of the steps the verifier would accept now. */
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

    private Map<String, Object> challengeState(TestIdentities.Employee employee) {
        return jdbc.queryForMap(
                "SELECT failed_attempts, consumed_at IS NOT NULL AS consumed,"
                        + " invalidated_at IS NOT NULL AS invalidated FROM mfa_challenge"
                        + " WHERE employee_id = ? ORDER BY created_at DESC, expires_at DESC LIMIT 1",
                employee.id());
    }

    private int challengeCount(TestIdentities.Employee employee) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM mfa_challenge WHERE employee_id = ?",
                Integer.class,
                employee.id());
    }

    private int sessionCount(TestIdentities.Employee employee) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE employee_id = ?",
                Integer.class,
                employee.id());
    }

    private int failedLoginCount(TestIdentities.Employee employee) {
        return jdbc.queryForObject(
                "SELECT failed_login_count FROM employee WHERE id = ?",
                Integer.class,
                employee.id());
    }

    private long lastStep(TestIdentities.Employee employee) {
        return jdbc.queryForObject(
                "SELECT mfa_totp_last_step FROM employee WHERE id = ?", Long.class, employee.id());
    }

    private boolean mfaEnabled(TestIdentities.Employee employee) {
        return jdbc.queryForObject(
                "SELECT mfa_enabled FROM employee WHERE id = ?", Boolean.class, employee.id());
    }

    private int auditCount(TestIdentities.Employee employee, String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE target_id = ? AND action = ?",
                Integer.class,
                employee.id().toString(),
                action);
    }

    private Map<String, Object> lastAudit(TestIdentities.Employee employee, String action) {
        return jdbc.queryForMap(
                "SELECT actor_id, details::text AS details FROM audit_log"
                        + " WHERE target_id = ? AND action = ? ORDER BY id DESC LIMIT 1",
                employee.id().toString(),
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
