package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.peoplehub.security.SecureTokens;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.PrivilegedFixture;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * B2-7 end to end through the real endpoints only (b2-7, B2-7/1-B2-7/27; Spec 2.1.3, 2.1.4, 2.1.5,
 * 3.3, 8.3; the B2 exit criterion "founder -> verification -> onboarding (MFA policy) ->
 * dashboard-ready"): a founder registers and signs in without MFA, chooses a policy, enrolls,
 * completes onboarding and is challenged from then on; a directly invited Admin must enroll at
 * first sign-in; a reset and a promotion put people back through enrollment; and across the whole
 * flow no password, secret, code, challenge token or token hash reaches a log line, an audit row or
 * a response that should not carry it. Runs on a controllable clock for exact TOTP steps.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class MfaEndToEndTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String ADMIN_PASSWORD = "Vq7#nR3tLm9@pZx2Kd";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private SecureTokens secureTokens;

    /** Every sensitive value seen during a test, for the whole-flow leak checks. */
    private final List<String> secrets = new ArrayList<>();

    @BeforeEach
    void now() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        secrets.clear();
        secrets.add(PASSWORD);
        secrets.add(ADMIN_PASSWORD);
    }

    @Test
    void theFounderJourneyFromRegistrationToAnMfaProtectedWorkspace(CapturedOutput output)
            throws Exception {
        String email = "founder-" + UUID.randomUUID() + "@example.com";
        String loginKey = registerAndVerify("Acme " + UUID.randomUUID(), email);

        // Registration and verification needed no MFA; the first sign-in is password-only.
        Map<String, Object> first = body(login(loginKey, email, PASSWORD));
        assertThat(first).containsKey("accessToken").doesNotContainKey("mfaRequired");
        String founder = bearer(first);
        Map<String, Object> me = me(founder);
        assertThat(organization(me)).containsEntry("onboardingCompleted", false);
        assertThat(mfa(me))
                .containsEntry("policy", "DISABLED")
                .containsEntry("enabled", false)
                .containsEntry("required", false)
                .containsEntry("showReminder", false);
        // DISABLED offers no enrollment.
        assertThat(status(call(post("/api/v1/me/mfa/enroll"), founder, null))).isEqualTo(409);

        // Security step: choose the policy (step-up with the password alone: no MFA yet).
        assertThat(status(setPolicy(founder, "REQUIRED_FOR_ADMINS"))).isEqualTo(403);
        stepUp(founder, Map.of("password", PASSWORD));
        assertThat(status(setPolicy(founder, "REQUIRED_FOR_ADMINS"))).isEqualTo(204);
        // Still signed in here, but now required: protected actions wait for enrollment.
        assertThat(mfa(me(founder)))
                .containsEntry("required", true)
                .containsEntry("enabled", false);
        MvcResult blocked = setPolicy(founder, "REQUIRED_FOR_ALL");
        assertThat(body(blocked))
                .containsEntry("type", "urn:peoplehub:problem:mfa-enrollment-required");

        // The founder enrolls from this session.
        byte[] secret = enroll(founder);
        assertThat(mfa(me(founder))).containsEntry("enabled", true);

        // Onboarding completes; /me shows it.
        assertThat(status(call(post("/api/v1/organization/onboarding/complete"), founder, null)))
                .isEqualTo(204);
        assertThat(organization(me(founder))).containsEntry("onboardingCompleted", true);

        // From now on every sign-in is challenged, even after relaxing the policy to DISABLED.
        clock.advance(Duration.ofSeconds(30));
        stepUp(founder, Map.of("password", PASSWORD, "code", code(secret)));
        assertThat(status(setPolicy(founder, "DISABLED"))).isEqualTo(204);
        clock.advance(Duration.ofSeconds(30));
        Map<String, Object> next = body(login(loginKey, email, PASSWORD));
        assertThat(next).containsEntry("mfaRequired", "CHALLENGE").doesNotContainKey("accessToken");
        String challengeToken = (String) next.get("challengeToken");
        secrets.add(challengeToken);
        secrets.add(secureTokens.hash(challengeToken));
        Map<String, Object> signedIn =
                body(
                        send(
                                post("/api/v1/auth/mfa/challenge"),
                                Map.of("challengeToken", challengeToken, "code", code(secret))));
        assertThat(signedIn).containsKey("accessToken");
        assertThat(mfa(me(bearer(signedIn)))).containsEntry("enabled", true);

        UUID organizationId = organizationId(loginKey);
        assertThat(actions(organizationId))
                .contains(
                        "LOGIN_SUCCEEDED",
                        "STEP_UP_VERIFIED",
                        "MFA_POLICY_CHANGED",
                        "MFA_ENROLLED",
                        "ORGANIZATION_ONBOARDING_COMPLETED");
        assertNothingLeaked(output, organizationId);
    }

    @Test
    void aDirectlyInvitedAdminEnrollsAtFirstSignInAndAgainAfterAReset(CapturedOutput output)
            throws Exception {
        String email = "founder-" + UUID.randomUUID() + "@example.com";
        String loginKey = registerAndVerify("Beta " + UUID.randomUUID(), email);
        String founder = bearer(body(login(loginKey, email, PASSWORD)));
        byte[] founderSecret = founderChoosesRequiredForAdminsAndEnrolls(founder);

        // Direct Admin invitation -> private password -> MFA enrollment -> Admin session.
        String adminEmail = "admin-" + UUID.randomUUID() + "@example.com";
        assertThat(
                        status(
                                call(
                                        post("/api/v1/super-admin/admins/invite"),
                                        founder,
                                        Map.of("name", "Ravi Admin", "email", adminEmail))))
                .isEqualTo(201);
        accept(inviteCode(adminEmail));
        Map<String, Object> adminLogin = body(login(loginKey, adminEmail, ADMIN_PASSWORD));
        assertThat(adminLogin).containsEntry("mfaRequired", "ENROLL");
        String enrollToken = (String) adminLogin.get("challengeToken");
        secrets.add(enrollToken);
        byte[] adminSecret = enrollAtSignIn(enrollToken);
        clock.advance(Duration.ofSeconds(30));

        // The founder resets the Admin's MFA: the Admin's sessions end, and the next sign-in is
        // the enrollment again (the policy still requires it).
        UUID adminId = employeeId(adminEmail);
        stepUp(founder, Map.of("password", PASSWORD, "code", code(founderSecret)));
        assertThat(
                        status(
                                call(
                                        post("/api/v1/admin/employees/" + adminId + "/mfa/reset"),
                                        founder,
                                        null)))
                .isEqualTo(204);
        Map<String, Object> again = body(login(loginKey, adminEmail, ADMIN_PASSWORD));
        assertThat(again).containsEntry("mfaRequired", "ENROLL");
        secrets.add((String) again.get("challengeToken"));
        secrets.add(Base32.encode(adminSecret));

        assertNothingLeaked(output, organizationId(loginKey));
    }

    @Test
    void aPromotedEmployeeEnrollsBeforeAnyAdminOperation(CapturedOutput output) throws Exception {
        String email = "founder-" + UUID.randomUUID() + "@example.com";
        String loginKey = registerAndVerify("Gamma " + UUID.randomUUID(), email);
        String founder = bearer(body(login(loginKey, email, PASSWORD)));
        byte[] founderSecret = founderChoosesRequiredForAdminsAndEnrolls(founder);

        String employeeEmail = "employee-" + UUID.randomUUID() + "@example.com";
        assertThat(
                        status(
                                call(
                                        post("/api/v1/admin/employees/invite"),
                                        founder,
                                        Map.of("name", "Riya Sharma", "email", employeeEmail))))
                .isEqualTo(201);
        accept(inviteCode(employeeEmail));
        // An Employee is not covered by REQUIRED_FOR_ADMINS: the password signs in.
        String employee = bearer(body(login(loginKey, employeeEmail, ADMIN_PASSWORD)));
        assertThat(mfa(me(employee))).containsEntry("required", false);

        UUID employeeId = employeeId(employeeEmail);
        assertThat(
                        status(
                                call(
                                        post(
                                                "/api/v1/super-admin/employees/"
                                                        + employeeId
                                                        + "/promote-admin"),
                                        founder,
                                        null)))
                .isEqualTo(204);
        // Their session ended; the next sign-in is the enrollment, before any Admin operation.
        assertThat(status(call(get("/api/v1/me"), employee, null))).isEqualTo(401);
        Map<String, Object> next = body(login(loginKey, employeeEmail, ADMIN_PASSWORD));
        assertThat(next).containsEntry("mfaRequired", "ENROLL").doesNotContainKey("accessToken");
        secrets.add((String) next.get("challengeToken"));
        String admin = bearer(signIn(enrollAtSignInSession((String) next.get("challengeToken"))));
        assertThat(me(admin)).containsEntry("role", "ADMIN");
        assertThat(mfa(me(admin))).containsEntry("enabled", true).containsEntry("required", true);

        assertNothingLeaked(output, organizationId(loginKey));
    }

    // ---------------------------------------------------------------------------------------
    // Flow helpers (real endpoints only)
    // ---------------------------------------------------------------------------------------

    private String registerAndVerify(String organizationName, String email) throws Exception {
        MvcResult registered =
                send(
                        post("/api/v1/public/organizations/register"),
                        Map.of(
                                "organizationName", organizationName,
                                "timezone", "Asia/Kolkata",
                                "founderFullName", "Jane Founder",
                                "companyEmail", email,
                                "password", PASSWORD,
                                "confirmPassword", PASSWORD,
                                "termsAccepted", true));
        assertThat(status(registered)).isEqualTo(201);
        String loginKey = (String) body(registered).get("organizationLoginKey");
        String verificationCode =
                jdbc.queryForObject(
                        "SELECT eo.payload -> 'attributes' ->> 'verificationCode' FROM email_outbox"
                                + " eo JOIN organization o ON o.id = eo.organization_id"
                                + " WHERE o.login_key_normalized = ?",
                        String.class,
                        loginKey);
        secrets.add(verificationCode);
        assertThat(
                        status(
                                send(
                                        post("/api/v1/public/organizations/verify-email"),
                                        Map.of("token", verificationCode))))
                .isEqualTo(200);
        return loginKey;
    }

    private String inviteCode(String email) throws Exception {
        String code =
                JSON.readTree(
                                jdbc.queryForObject(
                                        "SELECT payload::text FROM email_outbox WHERE recipient = ?",
                                        String.class,
                                        email))
                        .get("attributes")
                        .get("inviteCode")
                        .asString();
        secrets.add(code);
        return code;
    }

    private void accept(String inviteCode) throws Exception {
        assertThat(
                        status(
                                send(
                                        post(
                                                "/api/v1/public/invitations/"
                                                        + inviteCode
                                                        + "/accept"),
                                        Map.of(
                                                "password",
                                                ADMIN_PASSWORD,
                                                "confirmPassword",
                                                ADMIN_PASSWORD))))
                .isEqualTo(204);
    }

    private MvcResult login(String loginKey, String email, String password) throws Exception {
        MvcResult result =
                send(
                        post("/api/v1/auth/login"),
                        Map.of("organization", loginKey, "email", email, "password", password));
        assertThat(status(result)).isEqualTo(200);
        return result;
    }

    /**
     * The founder steps up with the password, sets REQUIRED_FOR_ADMINS, enrolls, and steps up again
     * with a TOTP code of the next time step; returns the secret.
     */
    private byte[] founderChoosesRequiredForAdminsAndEnrolls(String founder) throws Exception {
        stepUp(founder, Map.of("password", PASSWORD));
        assertThat(status(setPolicy(founder, "REQUIRED_FOR_ADMINS"))).isEqualTo(204);
        byte[] secret = enroll(founder);
        clock.advance(Duration.ofSeconds(30));
        stepUp(founder, Map.of("password", PASSWORD, "code", code(secret)));
        return secret;
    }

    /** Voluntary enrollment from a session; returns the secret. */
    private byte[] enroll(String bearer) throws Exception {
        MvcResult started = call(post("/api/v1/me/mfa/enroll"), bearer, null);
        assertThat(status(started)).isEqualTo(200);
        byte[] secret = rememberSecret(body(started));
        MvcResult confirmed =
                call(post("/api/v1/me/mfa/confirm"), bearer, Map.of("code", code(secret)));
        assertThat(status(confirmed)).isEqualTo(200);
        rememberCodes(body(confirmed));
        return secret;
    }

    /** Required enrollment at sign-in; returns the secret. */
    private byte[] enrollAtSignIn(String challengeToken) throws Exception {
        return enrollAtSignInSession(challengeToken).secret();
    }

    private record Enrolled(byte[] secret, Map<String, Object> session) {}

    private Enrolled enrollAtSignInSession(String challengeToken) throws Exception {
        MvcResult started =
                send(post("/api/v1/auth/mfa/enroll"), Map.of("challengeToken", challengeToken));
        assertThat(status(started)).isEqualTo(200);
        byte[] secret = rememberSecret(body(started));
        MvcResult confirmed =
                send(
                        post("/api/v1/auth/mfa/enroll/confirm"),
                        Map.of("challengeToken", challengeToken, "code", code(secret)));
        assertThat(status(confirmed)).isEqualTo(200);
        Map<String, Object> session = body(confirmed);
        assertThat(session).containsKey("accessToken");
        rememberCodes(session);
        return new Enrolled(secret, session);
    }

    private static Map<String, Object> signIn(Enrolled enrolled) {
        return enrolled.session();
    }

    private void stepUp(String bearer, Map<String, Object> request) throws Exception {
        assertThat(status(call(post("/api/v1/me/step-up"), bearer, request))).isEqualTo(204);
    }

    private MvcResult setPolicy(String bearer, String policy) throws Exception {
        return call(
                put("/api/v1/organization/security/mfa-policy"), bearer, Map.of("policy", policy));
    }

    private byte[] rememberSecret(Map<String, Object> enrollment) {
        String text = (String) enrollment.get("secret");
        secrets.add(text);
        secrets.add((String) enrollment.get("otpauthUri"));
        return Base32.decode(text).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private void rememberCodes(Map<String, Object> response) {
        for (String code : (List<String>) response.get("recoveryCodes")) {
            secrets.add(code);
            secrets.add(code.replace("-", ""));
        }
    }

    private String code(byte[] secret) {
        String code = Totp.code(secret, Totp.step(clock.instant()), 6);
        secrets.add("\"" + code + "\"");
        return code;
    }

    private Map<String, Object> me(String bearer) throws Exception {
        MvcResult result = call(get("/api/v1/me"), bearer, null);
        assertThat(status(result)).isEqualTo(200);
        return body(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mfa(Map<String, Object> me) {
        return (Map<String, Object>) me.get("mfa");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> organization(Map<String, Object> me) {
        return (Map<String, Object>) me.get("organization");
    }

    private static String bearer(Map<String, Object> tokenResponse) {
        return "Bearer " + tokenResponse.get("accessToken");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String bearer, Object body)
            throws Exception {
        request.header("Authorization", bearer);
        if (body != null) {
            request.contentType("application/json").content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mvc.perform(
                        request.contentType("application/json")
                                .content(JSON.writeValueAsString(body)))
                .andReturn();
    }

    private UUID organizationId(String loginKey) {
        return jdbc.queryForObject(
                "SELECT id FROM organization WHERE login_key_normalized = ?", UUID.class, loginKey);
    }

    private UUID employeeId(String email) {
        return jdbc.queryForObject(
                "SELECT id FROM employee WHERE email_normalized = ?", UUID.class, email);
    }

    private List<String> actions(UUID organizationId) {
        return jdbc.queryForList(
                "SELECT action FROM audit_log WHERE organization_id = ?",
                String.class,
                organizationId);
    }

    /**
     * No sensitive value of the flow in the captured logs, in any audit row of the organization, or
     * among the stored secrets in plain form.
     */
    private void assertNothingLeaked(CapturedOutput output, UUID organizationId) {
        String logs = output.getAll();
        String audit =
                String.join(
                        "\n",
                        jdbc.queryForList(
                                "SELECT details::text || ' ' || coalesce(target_id, '')"
                                        + " FROM audit_log WHERE organization_id = ?",
                                String.class,
                                organizationId));
        String stored =
                String.join(
                        "\n",
                        jdbc.queryForList(
                                "SELECT coalesce(mfa_totp_secret, '') || ' '"
                                        + " || coalesce(mfa_totp_pending_secret, '')"
                                        + " FROM employee WHERE organization_id = ?",
                                String.class,
                                organizationId));
        assertThat(secrets).isNotEmpty();
        for (String secret : secrets) {
            assertThat(logs).as("logs").doesNotContain(secret);
            assertThat(audit).as("audit rows").doesNotContain(secret);
            assertThat(stored).as("stored MFA secrets").doesNotContain(secret);
        }
        assertThat(audit).doesNotContain("@example.com").doesNotContain("otpauth");
        assertThat(logs).doesNotContain("otpauth://");
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }
}
