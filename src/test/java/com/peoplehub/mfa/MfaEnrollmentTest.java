package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Voluntary MFA enrollment from a signed-in session end to end (b2-7, B2-7/4, B2-7/6, B2-7/7,
 * B2-7/8, B2-7/24, B2-7/25; Spec 8.3, 13.0): {@code POST /me/mfa/enroll} and {@code POST
 * /me/mfa/confirm}, under each policy, with the stored state, the recovery codes, the audit row,
 * tenant isolation and the absence of secrets from logs. Runs on a controllable clock so TOTP codes
 * and recorded times are exact.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class MfaEnrollmentTest {

    private static final String ENROLL = "/api/v1/me/mfa/enroll";
    private static final String CONFIRM = "/api/v1/me/mfa/confirm";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;
    @Autowired private MutableClock clock;
    @Autowired private MfaSecretCipher cipher;
    @Autowired private SecureTokens secureTokens;

    private TestIdentities.Organization org;

    @BeforeEach
    void anOrganizationThatOffersMfa() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        org = TestIdentities.activeOrganization(jdbc);
        policy(org, MfaPolicy.OPTIONAL);
    }

    // ---------------------------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------------------------

    @Test
    void enrollReturnsANewSecretOnceAndStoresItEncryptedAndPending() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");

        MvcResult result = enroll(tokenFor(employee));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        Map<String, Object> body = body(result);
        assertThat(body).containsOnlyKeys("secret", "otpauthUri");
        String secret = (String) body.get("secret");
        assertThat(secret).matches("[A-Z2-7]{32}"); // 160 bits in base32
        assertThat((String) body.get("otpauthUri"))
                .startsWith("otpauth://totp/PeopleHub:")
                .contains("secret=" + secret)
                .contains("issuer=PeopleHub")
                .contains("algorithm=SHA1&digits=6&period=30");

        Map<String, Object> row = mfaRow(employee);
        assertThat(row.get("mfa_enabled")).isEqualTo(false);
        assertThat(row.get("mfa_enrolled_at")).isNull();
        String stored = (String) row.get("mfa_totp_secret");
        assertThat(stored).startsWith(TestMfaKeysEnvironment.KEY_ID + ":").doesNotContain(secret);
        assertThat(cipher.decrypt(stored, employee.organizationId(), employee.id()))
                .isEqualTo(Base32.decode(secret).orElseThrow());
    }

    @Test
    void confirmEnablesMfaAndReturnsTenRecoveryCodesOnce() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        String token = tokenFor(employee);
        byte[] secret = secretOf(enroll(token));
        clock.advance(Duration.ofSeconds(40));
        Instant confirmedAt = clock.instant();

        MvcResult result = confirm(token, currentCode(secret));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        Map<String, Object> body = body(result);
        assertThat(body).containsOnlyKeys("recoveryCodes");
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) body.get("recoveryCodes");
        assertThat(codes).hasSize(10).doesNotHaveDuplicates();
        assertThat(codes).allMatch(c -> c.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}"));

        Map<String, Object> row = mfaRow(employee);
        assertThat(row.get("mfa_enabled")).isEqualTo(true);
        assertThat(((Timestamp) row.get("mfa_enrolled_at")).toInstant()).isEqualTo(confirmedAt);
        assertThat(row.get("mfa_totp_last_step")).isEqualTo(Totp.step(confirmedAt));

        List<Map<String, Object>> stored =
                jdbc.queryForList(
                        "SELECT organization_id, code_hash, used_at, invalidated_at"
                                + " FROM mfa_recovery_code WHERE employee_id = ?",
                        employee.id());
        assertThat(stored).hasSize(10);
        assertThat(stored)
                .allSatisfy(
                        r -> {
                            assertThat(r.get("organization_id")).isEqualTo(org.id());
                            assertThat(r.get("used_at")).isNull();
                            assertThat(r.get("invalidated_at")).isNull();
                        });
        // Only hashes are stored, and each is the hash of the normalized code.
        assertThat(stored.stream().map(r -> (String) r.get("code_hash")))
                .containsExactlyInAnyOrderElementsOf(
                        codes.stream().map(c -> secureTokens.hash(c.replace("-", ""))).toList());
        assertThat(stored.toString()).doesNotContain(codes.get(0).replace("-", ""));
    }

    @Test
    void confirmingAuditsMfaEnrolledWithNoSecretOrCode() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        String token = tokenFor(employee);
        byte[] secret = secretOf(enroll(token));
        String code = currentCode(secret);

        List<String> codes = recoveryCodes(confirm(token, code));

        List<Map<String, Object>> audit =
                jdbc.queryForList(
                        "SELECT organization_id, actor_id, target_type, target_id,"
                                + " details::text AS details FROM audit_log"
                                + " WHERE action = 'MFA_ENROLLED' AND target_id = ?",
                        employee.id().toString());
        assertThat(audit).hasSize(1);
        Map<String, Object> row = audit.get(0);
        assertThat(row.get("organization_id")).isEqualTo(org.id());
        assertThat(row.get("actor_id")).isEqualTo(employee.id().toString());
        assertThat(row.get("target_type")).isEqualTo("EMPLOYEE");
        String details = (String) row.get("details");
        assertThat(JSON.readTree(details).get("attributes").get("method").asString())
                .isEqualTo("TOTP");
        assertThat(JSON.readTree(details).get("attributes").get("codesIssued").asInt())
                .isEqualTo(10);
        assertThat(details)
                .doesNotContain(Base32.encode(secret))
                .doesNotContain(code)
                .doesNotContain(codes.get(0))
                .doesNotContain(employee.email());
    }

    @Test
    void startingAgainBeforeConfirmingReplacesThePendingSecret() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        String token = tokenFor(employee);
        byte[] first = secretOf(enroll(token));
        byte[] second = secretOf(enroll(token));
        assertThat(second).isNotEqualTo(first);

        MvcResult withOld = confirm(token, currentCode(first));
        assertThat(withOld.getResponse().getStatus()).isEqualTo(400);
        assertThat(mfaRow(employee).get("mfa_enabled")).isEqualTo(false);

        assertThat(confirm(token, currentCode(second)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aCodeFromTheNeighbouringStepIsAcceptedButNotOneFurtherAway() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        String token = tokenFor(employee);
        byte[] secret = secretOf(enroll(token));
        long step = Totp.step(clock.instant());

        assertThat(confirm(token, Totp.code(secret, step - 2, 6)).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(confirm(token, Totp.code(secret, step - 1, 6)).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mfaRow(employee).get("mfa_totp_last_step")).isEqualTo(step - 1);
    }

    @Test
    void confirmingReplacesEarlierUnusedRecoveryCodes() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        // Left over from an earlier enrollment (in practice ended by a reset, B2-7/12).
        String oldHash = "old-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash)"
                        + " VALUES (?, ?, ?)",
                org.id(),
                employee.id(),
                oldHash);
        String token = tokenFor(employee);
        byte[] secret = secretOf(enroll(token));
        Instant confirmedAt = clock.instant();

        assertThat(confirm(token, currentCode(secret)).getResponse().getStatus()).isEqualTo(200);

        Timestamp invalidatedAt =
                jdbc.queryForObject(
                        "SELECT invalidated_at FROM mfa_recovery_code WHERE code_hash = ?",
                        Timestamp.class,
                        oldHash);
        assertThat(invalidatedAt.toInstant()).isEqualTo(confirmedAt);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM mfa_recovery_code WHERE employee_id = ?"
                                        + " AND invalidated_at IS NULL",
                                Integer.class,
                                employee.id()))
                .isEqualTo(10);
    }

    // ---------------------------------------------------------------------------------------
    // Policy
    // ---------------------------------------------------------------------------------------

    @Test
    void enrollmentIsNotOfferedWhileMfaIsDisabled() throws Exception {
        policy(org, MfaPolicy.DISABLED);
        TestIdentities.Employee employee = employee(org, "SUPER_ADMIN");

        MvcResult result = enroll(tokenFor(employee));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result))
                .containsEntry("type", "urn:peoplehub:problem:conflict")
                .containsEntry("detail", MfaEnrollmentService.NOT_OFFERED);
        assertThat(mfaRow(employee).get("mfa_totp_secret")).isNull();
    }

    @Test
    void aPendingEnrollmentCannotBeConfirmedOnceMfaIsDisabled() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        String token = tokenFor(employee);
        byte[] secret = secretOf(enroll(token));
        policy(org, MfaPolicy.DISABLED);

        MvcResult result = confirm(token, currentCode(secret));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result)).containsEntry("detail", MfaEnrollmentService.NOT_OFFERED);
        assertThat(mfaRow(employee).get("mfa_enabled")).isEqualTo(false);
    }

    @Test
    void everyoneMayEnrollUnderEveryPolicyThatOffersIt() throws Exception {
        for (MfaPolicy policy :
                List.of(
                        MfaPolicy.OPTIONAL,
                        MfaPolicy.REQUIRED_FOR_ADMINS,
                        MfaPolicy.REQUIRED_FOR_SELECTED_USERS,
                        MfaPolicy.REQUIRED_FOR_ALL)) {
            TestIdentities.Organization organization = TestIdentities.activeOrganization(jdbc);
            policy(organization, policy);
            // Covered and not covered alike (an Employee under REQUIRED_FOR_ADMINS, an unselected
            // person under REQUIRED_FOR_SELECTED_USERS).
            for (String role : List.of("EMPLOYEE", "ADMIN", "SUPER_ADMIN")) {
                TestIdentities.Employee employee = employee(organization, role);
                String token = tokenFor(employee);
                byte[] secret = secretOf(enroll(token));
                assertThat(confirm(token, currentCode(secret)).getResponse().getStatus())
                        .as("%s under %s", role, policy)
                        .isEqualTo(200);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Refusals
    // ---------------------------------------------------------------------------------------

    @Test
    void aWrongCodeIsAValidationErrorThatChangesNothingAndIsNotALockoutFailure() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        String token = tokenFor(employee);
        byte[] secret = secretOf(enroll(token));
        String wrong = currentCode(secret).equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 6; i++) {
            MvcResult result = confirm(token, wrong);
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"field\":\"code\"")
                    .doesNotContain(wrong);
        }

        Map<String, Object> row = mfaRow(employee);
        assertThat(row.get("mfa_enabled")).isEqualTo(false);
        assertThat(row.get("mfa_totp_last_step")).isNull();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT failed_login_count, locked_until FROM employee"
                                        + " WHERE id = ?",
                                employee.id()))
                .containsEntry("failed_login_count", 0)
                .containsEntry("locked_until", null);
        // Still possible with the right code.
        assertThat(confirm(token, currentCode(secret)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void malformedCodesAreRejectedByValidation() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        String token = tokenFor(employee);
        secretOf(enroll(token));

        for (String code : List.of("", "   ", "12345", "1234567", "abcdef", "1".repeat(17))) {
            assertThat(confirm(token, code).getResponse().getStatus()).as(code).isEqualTo(400);
        }
        MvcResult missing =
                mvc.perform(
                                post(CONFIRM)
                                        .header("Authorization", "Bearer " + token)
                                        .contentType("application/json")
                                        .content("{}"))
                        .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(mfaRow(employee).get("mfa_enabled")).isEqualTo(false);
    }

    @Test
    void confirmingWithoutAPendingEnrollmentIsAConflict() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");

        MvcResult result = confirm(tokenFor(employee), "123456");

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result)).containsEntry("detail", MfaEnrollmentService.NOTHING_TO_CONFIRM);
    }

    @Test
    void anEnrolledPersonCannotEnrollOrConfirmAgainWithoutStepUp() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        String token = tokenFor(employee);
        byte[] secret = secretOf(enroll(token));
        String code = currentCode(secret);
        assertThat(confirm(token, code).getResponse().getStatus()).isEqualTo(200);
        String storedSecret = (String) mfaRow(employee).get("mfa_totp_secret");

        // b2-7 C5 (B2-7/6): enrolling again while enrolled needs a fresh step-up.
        MvcResult again = enroll(token);
        assertThat(again.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(again)).containsEntry("type", "urn:peoplehub:problem:step-up-required");
        // The same code a second time (a replay) is refused too: nothing is pending.
        MvcResult replay = confirm(token, code);
        assertThat(replay.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(replay)).containsEntry("detail", MfaEnrollmentService.NOTHING_TO_CONFIRM);

        assertThat(mfaRow(employee).get("mfa_totp_secret")).isEqualTo(storedSecret);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM mfa_recovery_code WHERE employee_id = ?",
                                Integer.class,
                                employee.id()))
                .isEqualTo(10);
    }

    @Test
    void everyEndpointNeedsAnAccessToken() throws Exception {
        for (String path : List.of(ENROLL, CONFIRM, "/api/v1/me/mfa/reminder/dismiss")) {
            MvcResult result =
                    mvc.perform(post(path).contentType("application/json").content("{}"))
                            .andReturn();
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(401);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Tenant isolation and secrecy
    // ---------------------------------------------------------------------------------------

    @Test
    void enrollmentOnlyEverTouchesTheCallersOwnAccount() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        TestIdentities.Employee colleague = employee(org, "EMPLOYEE");
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);
        policy(other, MfaPolicy.OPTIONAL);
        TestIdentities.Employee elsewhere = employee(other, "EMPLOYEE");
        String token = tokenFor(employee);

        byte[] secret = secretOf(enroll(token));
        assertThat(confirm(token, currentCode(secret)).getResponse().getStatus()).isEqualTo(200);

        for (TestIdentities.Employee untouched : List.of(colleague, elsewhere)) {
            Map<String, Object> row = mfaRow(untouched);
            assertThat(row.get("mfa_enabled")).isEqualTo(false);
            assertThat(row.get("mfa_totp_secret")).isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM mfa_recovery_code WHERE employee_id = ?",
                                    Integer.class,
                                    untouched.id()))
                    .isZero();
        }
    }

    @Test
    void theStoredSecretIsBoundToItsAccount() throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        TestIdentities.Employee colleague = employee(org, "EMPLOYEE");
        String token = tokenFor(employee);
        secretOf(enroll(token));
        String stored = (String) mfaRow(employee).get("mfa_totp_secret");

        // Copied onto another account, the ciphertext cannot be decrypted there (B2-7/7).
        jdbc.update("UPDATE employee SET mfa_totp_secret = ? WHERE id = ?", stored, colleague.id());
        assertThatThrownBy(() -> cipher.decrypt(stored, colleague.organizationId(), colleague.id()))
                .isInstanceOf(MfaSecretUnreadableException.class);
    }

    @Test
    void noSecretUriOrCodeReachesTheLogs(CapturedOutput output) throws Exception {
        TestIdentities.Employee employee = employee(org, "EMPLOYEE");
        String token = tokenFor(employee);
        MvcResult started = enroll(token);
        Map<String, Object> body = body(started);
        byte[] secret = Base32.decode((String) body.get("secret")).orElseThrow();
        String code = currentCode(secret);
        confirm(token, code.equals("000000") ? "111111" : "000000");
        List<String> codes = recoveryCodes(confirm(token, code));

        String logs = output.getAll();
        assertThat(logs)
                .doesNotContain((String) body.get("secret"))
                .doesNotContain((String) body.get("otpauthUri"))
                .doesNotContain("otpauth://")
                .doesNotContain(employee.email());
        for (String recoveryCode : codes) {
            assertThat(logs)
                    .doesNotContain(recoveryCode)
                    .doesNotContain(recoveryCode.replace("-", ""));
        }
        assertThat(logs).doesNotContain("\"" + code + "\"");
    }

    @Test
    void theOpenApiDocumentDescribesTheEnrollmentEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/me/mfa/enroll'].post.security[0]",
                                hasKey("bearerAuth")))
                .andExpect(
                        jsonPath("$.paths['/api/v1/me/mfa/enroll'].post.responses['409']").exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/me/mfa/confirm'].post.security[0]",
                                hasKey("bearerAuth")))
                .andExpect(
                        jsonPath("$.paths['/api/v1/me/mfa/confirm'].post.responses['400']")
                                .exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/me/mfa/confirm'].post.responses['409']")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/me/mfa/reminder/dismiss'].post.security[0]",
                                hasKey("bearerAuth")))
                .andExpect(
                        jsonPath("$.components.schemas.MfaEnrollmentResponse.properties.otpauthUri")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.MfaRecoveryCodesResponse.properties.recoveryCodes")
                                .exists());
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private void policy(TestIdentities.Organization organization, MfaPolicy policy) {
        jdbc.update(
                "UPDATE organization SET mfa_policy = ? WHERE id = ?",
                policy.name(),
                organization.id());
    }

    private TestIdentities.Employee employee(
            TestIdentities.Organization organization, String role) {
        return TestIdentities.activeEmployee(jdbc, organization, role, null);
    }

    private String tokenFor(TestIdentities.Employee caller) {
        UUID session = TestIdentities.activeSession(jdbc, caller, clock.instant());
        return issuer.issue(caller.id(), caller.organizationId(), caller.role(), session).value();
    }

    private MvcResult enroll(String accessToken) throws Exception {
        return mvc.perform(post(ENROLL).header("Authorization", "Bearer " + accessToken))
                .andReturn();
    }

    private MvcResult confirm(String accessToken, String code) throws Exception {
        return mvc.perform(
                        post(CONFIRM)
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(Map.of("code", code))))
                .andReturn();
    }

    private byte[] secretOf(MvcResult enrollResult) throws Exception {
        assertThat(enrollResult.getResponse().getStatus()).isEqualTo(200);
        return Base32.decode((String) body(enrollResult).get("secret")).orElseThrow();
    }

    private String currentCode(byte[] secret) {
        return Totp.code(secret, Totp.step(clock.instant()), 6);
    }

    @SuppressWarnings("unchecked")
    private static List<String> recoveryCodes(MvcResult confirmResult) throws Exception {
        assertThat(confirmResult.getResponse().getStatus()).isEqualTo(200);
        List<String> codes = (List<String>) body(confirmResult).get("recoveryCodes");
        assertThat(new HashSet<>(codes)).hasSize(10);
        return codes;
    }

    private Map<String, Object> mfaRow(TestIdentities.Employee employee) {
        return jdbc.queryForMap(
                "SELECT mfa_enabled, mfa_totp_secret, mfa_enrolled_at, mfa_totp_last_step"
                        + " FROM employee WHERE id = ?",
                employee.id());
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }
}
