package com.peoplehub.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/v1/public/organizations/*} end to end (b2-2, Spec 2.1.3): a real, always-active
 * public controller, so tested with real HTTP requests through {@link MockMvc} against the full
 * application context, the same convention {@code EmailWebhookControllerTest} already established
 * for the other real (non-test-only) controller in this codebase.
 *
 * <p>The raw verification token is never returned by any endpoint (D29): tests read it back from
 * {@code email_outbox.payload}, which is exactly what the founder would see in the real email
 * ({@code EmailOutboxWriter} enqueues it in the same transaction as registration).
 */
@IntegrationTest
@AutoConfigureMockMvc
class RegistrationControllerTest {

    private static final String REGISTER = "/api/v1/public/organizations/register";
    private static final String VERIFY = "/api/v1/public/organizations/verify-email";
    private static final String RESEND = "/api/v1/public/organizations/resend-verification";

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    private static String uniqueOrgName() {
        return "Acme Corp " + UUID.randomUUID();
    }

    private static String uniqueEmail() {
        return "jane-" + UUID.randomUUID() + "@example.com";
    }

    private String registerBody(String orgName, String email, String password) {
        return """
                {"organizationName":"%s","timezone":"Asia/Kolkata","founderFullName":"Jane Doe",\
                "companyEmail":"%s","password":"%s","confirmPassword":"%s","termsAccepted":true}\
                """
                .formatted(orgName, email, password, password);
    }

    private String registerOrganizationAndReturnLoginKey(String orgName, String email)
            throws Exception {
        String response =
                mvc.perform(
                                post(REGISTER)
                                        .contentType("application/json")
                                        .content(
                                                registerBody(orgName, email, "xk9$mQ2vTz8!wLp4Rb")))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return response.replaceAll(".*\"organizationLoginKey\":\"([^\"]+)\".*", "$1");
    }

    private String rawTokenFor(String loginKey) {
        return jdbc.queryForObject(
                "SELECT eo.payload -> 'attributes' ->> 'verificationCode' FROM email_outbox eo"
                        + " JOIN organization o ON o.id = eo.organization_id"
                        + " WHERE o.login_key_normalized = ? AND eo.type ="
                        + " 'ORGANIZATION_VERIFICATION' ORDER BY eo.created_at DESC LIMIT 1",
                String.class,
                loginKey);
    }

    private long verificationEmailCount(String loginKey) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM email_outbox eo JOIN organization o ON o.id ="
                        + " eo.organization_id WHERE o.login_key_normalized = ? AND eo.type ="
                        + " 'ORGANIZATION_VERIFICATION'",
                Long.class,
                loginKey);
    }

    // ---- register ----

    @Test
    void registrationCreatesAPendingOrganizationAndFounder() throws Exception {
        String orgName = uniqueOrgName();
        String email = uniqueEmail();

        String response =
                mvc.perform(
                                post(REGISTER)
                                        .contentType("application/json")
                                        .content(
                                                registerBody(orgName, email, "xk9$mQ2vTz8!wLp4Rb")))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.organizationLoginKey").exists())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Minimal response (D29): no employee id, no organization id, no token.
        assertThat(response)
                .doesNotContain("employeeId")
                .doesNotContain("password")
                .doesNotContain("token");

        String loginKey = response.replaceAll(".*\"organizationLoginKey\":\"([^\"]+)\".*", "$1");
        Map<String, Object> org =
                jdbc.queryForMap(
                        "SELECT status, timezone FROM organization WHERE login_key_normalized = ?",
                        loginKey);
        assertThat(org.get("status")).isEqualTo("PENDING_VERIFICATION");
        assertThat(org.get("timezone")).isEqualTo("Asia/Kolkata");

        Map<String, Object> founder =
                jdbc.queryForMap(
                        "SELECT e.status, e.role, e.password_hash, e.email_normalized FROM employee"
                                + " e JOIN organization o ON o.id = e.organization_id WHERE"
                                + " o.login_key_normalized = ?",
                        loginKey);
        assertThat(founder.get("status")).isEqualTo("PENDING_VERIFICATION");
        assertThat(founder.get("role")).isEqualTo("SUPER_ADMIN");
        assertThat(founder.get("password_hash")).asString().startsWith("$argon2id$");
        assertThat(founder.get("email_normalized"))
                .isEqualTo(email.toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void registrationEnqueuesAVerificationEmailWithAToken() throws Exception {
        String orgName = uniqueOrgName();
        String loginKey = registerOrganizationAndReturnLoginKey(orgName, uniqueEmail());

        String rawToken = rawTokenFor(loginKey);

        assertThat(rawToken).isNotNull().hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void registrationWritesAnAuditEvent() throws Exception {
        String orgName = uniqueOrgName();
        String loginKey = registerOrganizationAndReturnLoginKey(orgName, uniqueEmail());

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT al.action, al.actor_id FROM audit_log al JOIN organization o ON"
                                + " o.id = al.organization_id WHERE o.login_key_normalized = ?"
                                + " AND al.action = 'ORGANIZATION_REGISTERED'",
                        loginKey);
        assertThat(row.get("actor_id")).isEqualTo("anonymous");
    }

    @Test
    void mismatchedPasswordsAreRejected() throws Exception {
        String body =
                """
                {"organizationName":"%s","timezone":"Asia/Kolkata","founderFullName":"Jane Doe",\
                "companyEmail":"%s","password":"xk9$mQ2vTz8!wLp4Rb","confirmPassword":"different",\
                "termsAccepted":true}\
                """
                        .formatted(uniqueOrgName(), uniqueEmail());

        mvc.perform(post(REGISTER).contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:validation-error"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("confirmPassword"));
    }

    @Test
    void anInvalidTimeZoneIsRejected() throws Exception {
        String body =
                """
                {"organizationName":"%s","timezone":"Not/AZone","founderFullName":"Jane Doe",\
                "companyEmail":"%s","password":"xk9$mQ2vTz8!wLp4Rb","confirmPassword":\
                "xk9$mQ2vTz8!wLp4Rb","termsAccepted":true}\
                """
                        .formatted(uniqueOrgName(), uniqueEmail());

        mvc.perform(post(REGISTER).contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("timezone"));
    }

    @Test
    void aWeakPasswordIsRejected() throws Exception {
        String body = registerBody(uniqueOrgName(), uniqueEmail(), "short1");

        mvc.perform(post(REGISTER).contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    @Test
    void termsMustBeAccepted() throws Exception {
        String body =
                """
                {"organizationName":"%s","timezone":"Asia/Kolkata","founderFullName":"Jane Doe",\
                "companyEmail":"%s","password":"xk9$mQ2vTz8!wLp4Rb","confirmPassword":\
                "xk9$mQ2vTz8!wLp4Rb","termsAccepted":false}\
                """
                        .formatted(uniqueOrgName(), uniqueEmail());

        mvc.perform(post(REGISTER).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDuplicateOrganizationNameIsAConflict() throws Exception {
        String orgName = uniqueOrgName();
        mvc.perform(
                        post(REGISTER)
                                .contentType("application/json")
                                .content(
                                        registerBody(orgName, uniqueEmail(), "xk9$mQ2vTz8!wLp4Rb")))
                .andExpect(status().isCreated());

        mvc.perform(
                        post(REGISTER)
                                .contentType("application/json")
                                .content(
                                        registerBody(orgName, uniqueEmail(), "xk9$mQ2vTz8!wLp4Rb")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:conflict"));
    }

    @Test
    void responseBodyNeverLeaksAStackTraceOrPasswordOnValidationFailure() throws Exception {
        String body = registerBody(uniqueOrgName(), uniqueEmail(), "short1");

        String response =
                mvc.perform(post(REGISTER).contentType("application/json").content(body))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response).doesNotContain("short1").doesNotContain("Exception");
    }

    // ---- verify-email ----

    @Test
    void verifyingWithTheRealTokenActivatesOrganizationAndFounder() throws Exception {
        String loginKey = registerOrganizationAndReturnLoginKey(uniqueOrgName(), uniqueEmail());
        String rawToken = rawTokenFor(loginKey);

        mvc.perform(
                        post(VERIFY)
                                .contentType("application/json")
                                .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));

        Map<String, Object> org =
                jdbc.queryForMap(
                        "SELECT status FROM organization WHERE login_key_normalized = ?", loginKey);
        assertThat(org.get("status")).isEqualTo("ACTIVE");

        Map<String, Object> founder =
                jdbc.queryForMap(
                        "SELECT e.status FROM employee e JOIN organization o ON o.id ="
                                + " e.organization_id WHERE o.login_key_normalized = ?",
                        loginKey);
        assertThat(founder.get("status")).isEqualTo("ACTIVE");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_log al JOIN organization o ON o.id ="
                                        + " al.organization_id WHERE o.login_key_normalized = ? AND"
                                        + " al.action = 'ORGANIZATION_EMAIL_VERIFIED'",
                                Long.class,
                                loginKey))
                .isEqualTo(1);
    }

    @Test
    void aTokenCanOnlyBeConsumedOnce() throws Exception {
        String loginKey = registerOrganizationAndReturnLoginKey(uniqueOrgName(), uniqueEmail());
        String rawToken = rawTokenFor(loginKey);
        mvc.perform(
                post(VERIFY)
                        .contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\"}"));

        mvc.perform(
                        post(VERIFY)
                                .contentType("application/json")
                                .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false));
    }

    @Test
    void aNonexistentTokenReturnsTheSameGenericFalseOutcome() throws Exception {
        mvc.perform(
                        post(VERIFY)
                                .contentType("application/json")
                                .content("{\"token\":\"" + "0".repeat(64) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false));
    }

    // ---- resend-verification ----

    @Test
    void resendingForAMatchingPendingFounderEnqueuesAnotherVerificationEmail() throws Exception {
        String orgName = uniqueOrgName();
        String email = uniqueEmail();
        String loginKey = registerOrganizationAndReturnLoginKey(orgName, email);
        assertThat(verificationEmailCount(loginKey)).isEqualTo(1);

        String message =
                mvc.perform(
                                post(RESEND)
                                        .contentType("application/json")
                                        .content(
                                                "{\"organizationLoginKey\":\""
                                                        + loginKey
                                                        + "\",\"companyEmail\":\""
                                                        + email
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(verificationEmailCount(loginKey)).isEqualTo(2);
        assertThat(message).contains("If that organization and email match a pending registration");
    }

    @Test
    void resendingForANonMatchingOrganizationOrEmailGivesTheIdenticalGenericResponse()
            throws Exception {
        String realLoginKey = registerOrganizationAndReturnLoginKey(uniqueOrgName(), uniqueEmail());

        String matching =
                mvc.perform(
                                post(RESEND)
                                        .contentType("application/json")
                                        .content(
                                                "{\"organizationLoginKey\":\"nonexistent-org-"
                                                        + UUID.randomUUID()
                                                        + "\",\"companyEmail\":\""
                                                        + uniqueEmail()
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String realOrgWrongEmail =
                mvc.perform(
                                post(RESEND)
                                        .contentType("application/json")
                                        .content(
                                                "{\"organizationLoginKey\":\""
                                                        + realLoginKey
                                                        + "\",\"companyEmail\":\""
                                                        + uniqueEmail()
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(matching).isEqualTo(realOrgWrongEmail);
        // Neither request matched anything real, so no new email was enqueued for the real org.
        assertThat(verificationEmailCount(realLoginKey)).isEqualTo(1);
    }

    @Test
    void resendingAfterVerificationDoesNotSendAnotherEmail() throws Exception {
        String email = uniqueEmail();
        String loginKey = registerOrganizationAndReturnLoginKey(uniqueOrgName(), email);
        String rawToken = rawTokenFor(loginKey);
        mvc.perform(
                post(VERIFY)
                        .contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\"}"));
        assertThat(verificationEmailCount(loginKey)).isEqualTo(1);

        mvc.perform(
                        post(RESEND)
                                .contentType("application/json")
                                .content(
                                        "{\"organizationLoginKey\":\""
                                                + loginKey
                                                + "\",\"companyEmail\":\""
                                                + email
                                                + "\"}"))
                .andExpect(status().isOk());

        // The founder is ACTIVE now, not PENDING_VERIFICATION: resend's own SELECT excludes it.
        assertThat(verificationEmailCount(loginKey)).isEqualTo(1);
    }

    @Test
    void unknownFieldsAndMissingFieldsAreRejectedAsBadRequestNotServerErrors() throws Exception {
        mvc.perform(post(REGISTER).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
}
