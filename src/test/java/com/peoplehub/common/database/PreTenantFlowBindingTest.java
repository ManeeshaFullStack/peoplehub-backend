package com.peoplehub.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TenantWriteProbe;
import com.peoplehub.support.TestIdentities;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every public flow binds its transaction to the organization it resolved before it writes a tenant
 * row (b2-8 C3; O1, O3, O6). Row-level security is not on yet (V25), so the binding cannot be seen
 * through a policy; instead {@link TenantWriteProbe} records, for each row the application writes,
 * the row's organization and the transaction's tenant setting at that moment, and every write must
 * carry its own organization. The one designed exception is the organization row that registration
 * creates, through the V24 function, before any tenant exists. The MFA step of a sign-in is covered
 * the same way by {@code MfaSignInTenantBindingTest}.
 *
 * <p>The flows' observable answers are unchanged and covered by their own tests; this class also
 * checks the unknown-organization and invalid-token paths write nothing and that no {@link
 * TenantContext} or tenant setting outlives a request.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PreTenantFlowBindingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REFRESH_COOKIE = "__Secure-peoplehub_rt";
    private static final String CSRF_COOKIE = "__Secure-peoplehub_csrf";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String PASSWORD = "Probe-pass " + UUID.randomUUID();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate fixture;
    @Autowired private JdbcClient applicationJdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordHasher passwordHasher;

    private TenantWriteProbe probe;

    @BeforeEach
    void installTheProbe() {
        probe = new TenantWriteProbe(fixture);
        probe.install();
    }

    @AfterEach
    void removeTheProbe() {
        probe.remove();
    }

    // ---- flows ----

    @Test
    void registrationResendAndVerificationBindEveryWriteToTheNewOrganization() throws Exception {
        String email = "founder-" + UUID.randomUUID() + "@example.com";
        MvcResult registered =
                send(
                        post("/api/v1/public/organizations/register"),
                        Map.of(
                                "organizationName",
                                "Probe " + UUID.randomUUID(),
                                "timezone",
                                "Asia/Kolkata",
                                "founderFullName",
                                "Jane Founder",
                                "companyEmail",
                                email,
                                "password",
                                PASSWORD,
                                "confirmPassword",
                                PASSWORD,
                                "termsAccepted",
                                true));
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        String loginKey = (String) body(registered).get("organizationLoginKey");
        UUID org = organizationId(loginKey);

        assertThat(
                        send(
                                        post("/api/v1/public/organizations/resend-verification"),
                                        Map.of(
                                                "organizationLoginKey",
                                                loginKey,
                                                "companyEmail",
                                                email))
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        String code =
                fixture.queryForObject(
                        "SELECT payload -> 'attributes' ->> 'verificationCode' FROM email_outbox"
                                + " WHERE organization_id = ? ORDER BY id DESC LIMIT 1",
                        String.class,
                        org);
        assertThat(
                        send(
                                        post("/api/v1/public/organizations/verify-email"),
                                        Map.of("token", code))
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(
                        fixture.queryForObject(
                                "SELECT status FROM organization WHERE id = ?", String.class, org))
                .isEqualTo("ACTIVE");

        assertThat(probe.unboundWrites())
                .as("only the organization row itself is created before a tenant exists")
                .containsExactly("organization INSERT");
        assertThat(probe.boundWrites(org))
                .contains(
                        "organization UPDATE",
                        "employee INSERT",
                        "organization_verification_token INSERT",
                        "organization_verification_token UPDATE",
                        "email_outbox INSERT",
                        "audit_log INSERT");
    }

    @Test
    void loginRefreshAndLogoutBindEveryWrite() throws Exception {
        TestIdentities.Employee employee = activeEmployee("EMPLOYEE");

        Session session = login(employee);
        MvcResult refreshed =
                mvc.perform(session.withCookies(post("/api/v1/auth/refresh"))).andReturn();
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        Session rotated = session(refreshed);
        assertThat(
                        mvc.perform(rotated.withCookies(post("/api/v1/auth/logout")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);

        assertThat(probe.unboundWrites()).isEmpty();
        assertThat(probe.boundWrites(employee.organizationId()))
                .contains("refresh_token INSERT", "refresh_token UPDATE", "audit_log INSERT");
    }

    @Test
    void invitationPreviewAndAcceptanceBindEveryWrite() throws Exception {
        TestIdentities.Employee admin = activeEmployee("ADMIN");
        Session session = login(admin);
        String inviteeEmail = "invitee-" + UUID.randomUUID() + "@example.com";
        MvcResult invited =
                mvc.perform(
                                post("/api/v1/admin/employees/invite")
                                        .header("Authorization", "Bearer " + session.accessToken())
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "Ivan Invitee",
                                                                "email",
                                                                inviteeEmail))))
                        .andReturn();
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        String inviteCode =
                fixture.queryForObject(
                        "SELECT payload -> 'attributes' ->> 'inviteCode' FROM email_outbox"
                                + " WHERE recipient = ?",
                        String.class,
                        inviteeEmail);

        assertThat(
                        mvc.perform(get("/api/v1/public/invitations/" + inviteCode + "/preview"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(TenantContext.current()).isEmpty();
        assertThat(
                        send(
                                        post(
                                                "/api/v1/public/invitations/"
                                                        + inviteCode
                                                        + "/accept"),
                                        Map.of("password", PASSWORD, "confirmPassword", PASSWORD))
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);

        assertThat(probe.unboundWrites()).isEmpty();
        assertThat(probe.boundWrites(admin.organizationId()))
                .contains("employee UPDATE", "employee_invitation UPDATE", "audit_log INSERT");
    }

    @Test
    void forgotAndResetPasswordBindEveryWrite() throws Exception {
        TestIdentities.Employee employee = activeEmployee("EMPLOYEE");

        assertThat(
                        send(
                                        post("/api/v1/auth/forgot-password"),
                                        Map.of(
                                                "organization",
                                                employee.organizationLoginKey(),
                                                "email",
                                                employee.email()))
                                .getResponse()
                                .getStatus())
                .isEqualTo(202);
        String resetCode =
                fixture.queryForObject(
                        "SELECT payload -> 'attributes' ->> 'resetCode' FROM email_outbox"
                                + " WHERE recipient = ?",
                        String.class,
                        employee.email());
        String newPassword = "a brand new passphrase " + UUID.randomUUID();
        assertThat(
                        send(
                                        post("/api/v1/auth/reset-password"),
                                        Map.of(
                                                "token", resetCode,
                                                "password", newPassword,
                                                "confirmPassword", newPassword))
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);

        assertThat(probe.unboundWrites()).isEmpty();
        assertThat(probe.boundWrites(employee.organizationId()))
                .contains(
                        "password_reset_token INSERT",
                        "password_reset_token UPDATE",
                        "email_outbox INSERT",
                        "employee UPDATE",
                        "audit_log INSERT");
    }

    // ---- unknown organizations and tokens ----

    @Test
    void unknownOrganizationsAndTokensWriteNoTenantRowAndLeaveNoTenantBehind() throws Exception {
        String unknownKey = "nobody-" + UUID.randomUUID();
        String unknownToken = "not-a-token-" + UUID.randomUUID();

        assertThat(
                        send(
                                        post("/api/v1/auth/login"),
                                        Map.of(
                                                "organization", unknownKey,
                                                "email", "x@example.com",
                                                "password", PASSWORD))
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        assertThat(TenantContext.current()).isEmpty();
        assertThat(
                        send(
                                        post("/api/v1/auth/forgot-password"),
                                        Map.of(
                                                "organization",
                                                unknownKey,
                                                "email",
                                                "x@example.com"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(202);
        assertThat(
                        send(
                                        post("/api/v1/public/organizations/resend-verification"),
                                        Map.of(
                                                "organizationLoginKey",
                                                unknownKey,
                                                "companyEmail",
                                                "x@example.com"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(
                        send(
                                        post("/api/v1/public/organizations/verify-email"),
                                        Map.of("token", unknownToken))
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(
                        send(
                                        post("/api/v1/auth/reset-password"),
                                        Map.of(
                                                "token", unknownToken,
                                                "password", PASSWORD,
                                                "confirmPassword", PASSWORD))
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        mvc.perform(get("/api/v1/public/invitations/" + unknownToken + "/preview"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        send(
                                        post(
                                                "/api/v1/public/invitations/"
                                                        + unknownToken
                                                        + "/accept"),
                                        Map.of("password", PASSWORD, "confirmPassword", PASSWORD))
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(
                        send(
                                        post("/api/v1/auth/mfa/challenge"),
                                        Map.of("challengeToken", unknownToken, "code", "123456"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        Session nobody = new Session("none", unknownToken, "csrf-" + UUID.randomUUID());
        assertThat(
                        mvc.perform(nobody.withCookies(post("/api/v1/auth/refresh")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        assertThat(
                        mvc.perform(nobody.withCookies(post("/api/v1/auth/logout")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);

        assertThat(TenantContext.current()).isEmpty();
        assertThat(probe.applicationWrites())
                .as("no tenant row written by the application")
                .isZero();
    }

    @Test
    void noTenantSettingOutlivesAFlowOnThePooledConnections() throws Exception {
        TestIdentities.Employee employee = activeEmployee("EMPLOYEE");
        Session session = login(employee);
        mvc.perform(session.withCookies(post("/api/v1/auth/refresh"))).andReturn();
        send(
                post("/api/v1/auth/forgot-password"),
                Map.of("organization", employee.organizationLoginKey(), "email", employee.email()));
        assertThat(TenantContext.current()).isEmpty();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // More transactions than the pool has connections, so every pooled connection is seen.
        for (int i = 0; i < 25; i++) {
            String setting =
                    tx.execute(
                            status ->
                                    applicationJdbc
                                            .sql(
                                                    "SELECT current_setting("
                                                            + "'peoplehub.organization_id', true)")
                                            .query(String.class)
                                            .optional()
                                            .orElse(null));
            assertThat(setting).as("pooled transaction %d", i).isIn(null, "");
        }
    }

    // ---- helpers ----

    private TestIdentities.Employee activeEmployee(String role) {
        TestIdentities.Organization org = TestIdentities.activeOrganization(fixture);
        return TestIdentities.activeEmployee(fixture, org, role, passwordHasher.hash(PASSWORD));
    }

    private UUID organizationId(String loginKey) {
        return fixture.queryForObject(
                "SELECT id FROM organization WHERE login_key_normalized = ?", UUID.class, loginKey);
    }

    private MvcResult loginResult(TestIdentities.Employee employee) throws Exception {
        MvcResult result =
                send(
                        post("/api/v1/auth/login"),
                        Map.of(
                                "organization", employee.organizationLoginKey(),
                                "email", employee.email(),
                                "password", PASSWORD));
        assertThat(TenantContext.current()).isEmpty();
        return result;
    }

    private Session login(TestIdentities.Employee employee) throws Exception {
        MvcResult result = loginResult(employee);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return session(result);
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        MvcResult result =
                mvc.perform(
                                request.contentType("application/json")
                                        .content(JSON.writeValueAsString(body)))
                        .andReturn();
        assertThat(TenantContext.current()).as("no tenant left on the thread").isEmpty();
        return result;
    }

    private static Session session(MvcResult result) throws Exception {
        Map<String, Object> body = body(result);
        return new Session(
                (String) body.get("accessToken"),
                result.getResponse().getCookie(REFRESH_COOKIE).getValue(),
                (String) body.get("csrfToken"));
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private record Session(String accessToken, String refreshToken, String csrfToken) {
        MockHttpServletRequestBuilder withCookies(MockHttpServletRequestBuilder request) {
            return request.cookie(
                            new Cookie(REFRESH_COOKIE, refreshToken),
                            new Cookie(CSRF_COOKIE, csrfToken))
                    .header(CSRF_HEADER, csrfToken);
        }
    }
}
