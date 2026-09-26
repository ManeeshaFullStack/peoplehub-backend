package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.SignedJWT;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/auth/login} end to end (b2-3, B2-3/6, B2-3/11-B2-3/13, B2-3/15, B2-3/17; Spec
 * 2.1.6). Runs with an app origin configured, so the Origin check and CORS are exercised, and with
 * the password hasher spied on, to prove every attempt runs exactly one Argon2 verification.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "peoplehub.security.app-origin=" + LoginTest.APP_ORIGIN)
@ExtendWith(OutputCaptureExtension.class)
class LoginTest {

    static final String APP_ORIGIN = "https://app.peoplehub.test";

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @MockitoSpyBean private PasswordHasher passwordHasher;

    private static String loginBody(String organization, String email, String password) {
        return JSON.writeValueAsString(
                Map.of("organization", organization, "email", email, "password", password));
    }

    private static MockHttpServletRequestBuilder login(
            String organization, String email, String password) {
        return post(LOGIN)
                .contentType("application/json")
                .content(loginBody(organization, email, password));
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private TestIdentities.Employee employeeWithPassword(TestIdentities.Organization org) {
        return TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", passwordHasher.hash(PASSWORD));
    }

    // ---- success ----

    @Test
    void aFounderRegistersVerifiesAndLogsInWithTheLoginKeyOrTheOrganizationName() throws Exception {
        String orgName = "Acme Corp " + UUID.randomUUID();
        String email = "founder-" + UUID.randomUUID() + "@example.com";
        String loginKey = registerAndVerify(orgName, email);

        for (String organization : List.of(loginKey, orgName, "  " + orgName.toUpperCase())) {
            MvcResult result = mvc.perform(login(organization, email, PASSWORD)).andReturn();

            assertThat(result.getResponse().getStatus()).as(organization).isEqualTo(200);
            Map<String, Object> body = body(result);
            assertThat(body)
                    .containsOnlyKeys("accessToken", "tokenType", "expiresIn", "csrfToken")
                    .containsEntry("tokenType", "Bearer")
                    .containsEntry("expiresIn", 900);

            String accessToken = (String) body.get("accessToken");
            MvcResult me =
                    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                            .andReturn();
            assertThat(me.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(me)).containsEntry("email", email).containsEntry("role", "SUPER_ADMIN");
        }
    }

    @Test
    void theCookiesAreHttpOnlySecureStrictAndScopedToTheAuthEndpoints() throws Exception {
        TestIdentities.Employee employee =
                employeeWithPassword(TestIdentities.activeOrganization(jdbc));

        MvcResult result =
                mvc.perform(login(employee.organizationLoginKey(), employee.email(), PASSWORD))
                        .andReturn();

        List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
        assertThat(setCookies).hasSize(2);
        assertThat(setCookies)
                .anySatisfy(c -> assertThat(c).startsWith("__Secure-peoplehub_rt="))
                .anySatisfy(c -> assertThat(c).startsWith("__Secure-peoplehub_csrf="))
                .allSatisfy(
                        c ->
                                assertThat(c)
                                        .contains("Path=/api/v1/auth")
                                        .contains("Max-Age=2592000")
                                        .contains("Secure")
                                        .contains("HttpOnly")
                                        .contains("SameSite=Strict")
                                        .doesNotContain("Domain="));

        String csrfCookie = result.getResponse().getCookie("__Secure-peoplehub_csrf").getValue();
        assertThat(body(result)).containsEntry("csrfToken", csrfCookie);

        // Only the refresh token's hash is stored; the raw value is only in the cookie.
        String raw = result.getResponse().getCookie("__Secure-peoplehub_rt").getValue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(raw);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refresh_token WHERE token_hash = ?",
                                Integer.class,
                                raw))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refresh_token WHERE employee_id = ?"
                                        + " AND organization_id = ? AND NOT revoked",
                                Integer.class,
                                employee.id(),
                                employee.organizationId()))
                .isEqualTo(1);
    }

    @Test
    void theAccessTokenCarriesTheSessionOfTheNewRefreshTokenFamily() throws Exception {
        TestIdentities.Employee employee =
                employeeWithPassword(TestIdentities.activeOrganization(jdbc));

        MvcResult result =
                mvc.perform(login(employee.organizationLoginKey(), employee.email(), PASSWORD))
                        .andReturn();

        SignedJWT jwt = SignedJWT.parse((String) body(result).get("accessToken"));
        UUID family =
                jdbc.queryForObject(
                        "SELECT family_id FROM refresh_token WHERE employee_id = ?",
                        UUID.class,
                        employee.id());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("sid")).isEqualTo(family.toString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("org"))
                .isEqualTo(employee.organizationId().toString());
    }

    // ---- failure: one generic answer ----

    /**
     * b2-8 C3: login now asks V24 for the organization first, so an unknown organization reads no
     * tenant row, while a known one continues inside it. Whatever the client can observe must still
     * be identical: status, every header except the per-request correlation id, the whole body
     * except its per-request fields, exactly one Argon2 check, and no audit row or other tenant
     * write for either. (The extra resolver query on the known path is a sub-millisecond timing
     * difference, accepted for C3 and examined in C5's adversarial enumeration tests.)
     */
    @Test
    void aKnownAndAnUnknownOrganizationFailIdenticallyInEverythingTheClientSees() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee active = employeeWithPassword(org);
        long auditBefore = jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class);

        clearInvocations(passwordHasher);
        MvcResult known =
                mvc.perform(login(org.loginKey(), active.email(), PASSWORD + "x")).andReturn();
        verify(passwordHasher, times(1)).matches(anyString(), anyString());
        clearInvocations(passwordHasher);
        MvcResult unknown =
                mvc.perform(login("no-such-org-" + UUID.randomUUID(), active.email(), PASSWORD))
                        .andReturn();
        verify(passwordHasher, times(1)).matches(anyString(), anyString());

        assertThat(unknown.getResponse().getStatus())
                .isEqualTo(known.getResponse().getStatus())
                .isEqualTo(401);
        assertThat(comparableHeaders(unknown)).isEqualTo(comparableHeaders(known));
        Map<String, Object> knownBody = body(known);
        Map<String, Object> unknownBody = body(unknown);
        for (Map<String, Object> body : List.of(knownBody, unknownBody)) {
            body.remove("instance");
            body.remove("correlationId");
        }
        assertThat(unknownBody).isEqualTo(knownBody);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class))
                .as("a failed login is never audited, known organization or not (B0-6/16)")
                .isEqualTo(auditBefore);
    }

    /** Every response header, except the correlation id every request gets its own of. */
    private static Map<String, List<String>> comparableHeaders(MvcResult result) {
        Map<String, List<String>> headers = new TreeMap<>();
        for (String name : result.getResponse().getHeaderNames()) {
            if (!name.equalsIgnoreCase("X-Correlation-Id")) {
                headers.put(name, result.getResponse().getHeaders(name));
            }
        }
        return headers;
    }

    @Test
    void everyFailureCauseGetsTheSameAnswerAndRunsExactlyOneArgon2Check() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee active = employeeWithPassword(org);
        TestIdentities.Employee deactivated = employeeWithPassword(org);
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", deactivated.id());
        TestIdentities.Employee noPassword =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        TestIdentities.Organization suspended = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee inSuspended = employeeWithPassword(suspended);
        jdbc.update("UPDATE organization SET status = 'SUSPENDED' WHERE id = ?", suspended.id());
        String unverifiedEmail = "founder-" + UUID.randomUUID() + "@example.com";
        String unverifiedKey = register("Pending Org " + UUID.randomUUID(), unverifiedEmail);

        Map<String, String[]> attempts =
                Map.of(
                        "unknown organization",
                        new String[] {"no-such-org-" + UUID.randomUUID(), active.email(), PASSWORD},
                        "unknown email",
                        new String[] {
                            org.loginKey(), "nobody-" + UUID.randomUUID() + "@x.io", PASSWORD
                        },
                        "wrong password",
                        new String[] {org.loginKey(), active.email(), PASSWORD + "x"},
                        "deactivated employee",
                        new String[] {org.loginKey(), deactivated.email(), PASSWORD},
                        "no password set",
                        new String[] {org.loginKey(), noPassword.email(), PASSWORD},
                        "suspended organization",
                        new String[] {suspended.loginKey(), inSuspended.email(), PASSWORD},
                        "unverified founder",
                        new String[] {unverifiedKey, unverifiedEmail, PASSWORD},
                        "organization with no letter or digit",
                        new String[] {"---", active.email(), PASSWORD});

        Map<String, Object> first = null;
        for (Map.Entry<String, String[]> attempt : attempts.entrySet()) {
            String[] input = attempt.getValue();
            clearInvocations(passwordHasher);

            MvcResult result = mvc.perform(login(input[0], input[1], input[2])).andReturn();

            assertThat(result.getResponse().getStatus()).as(attempt.getKey()).isEqualTo(401);
            assertThat(result.getResponse().getHeaders("Set-Cookie"))
                    .as(attempt.getKey())
                    .isEmpty();
            verify(passwordHasher, times(1)).matches(anyString(), anyString());
            Map<String, Object> body = body(result);
            body.remove("instance");
            body.remove("correlationId");
            if (first == null) {
                first = body;
            }
            assertThat(body).as(attempt.getKey()).isEqualTo(first);
        }
        assertThat(first)
                .containsOnlyKeys("type", "title", "status", "detail")
                .containsEntry("type", "urn:peoplehub:problem:unauthorized")
                .containsEntry("detail", "We couldn't sign you in with those details.");
    }

    @Test
    void theWrongPasswordDoesNotLeakAnythingIntoTheLogs(CapturedOutput output) throws Exception {
        TestIdentities.Employee employee =
                employeeWithPassword(TestIdentities.activeOrganization(jdbc));
        String typedPassword = "Wr0ng-" + UUID.randomUUID();

        mvc.perform(login(employee.organizationLoginKey(), employee.email(), typedPassword))
                .andReturn();
        mvc.perform(login(employee.organizationLoginKey(), employee.email(), PASSWORD)).andReturn();

        // The application's own log lines (structured JSON). MockMvc's request dump, printed by the
        // test framework when a test fails, is not an application log and is excluded.
        String applicationLogs =
                output.getAll()
                        .lines()
                        .filter(line -> line.startsWith("{\"@timestamp\""))
                        .reduce("", (all, line) -> all + line + "\n");
        assertThat(applicationLogs)
                .contains("/api/v1/auth/login")
                .doesNotContain(typedPassword)
                .doesNotContain(PASSWORD)
                .doesNotContain(employee.email());
    }

    // ---- what is recorded ----

    @Test
    void everyAttemptIsRecordedAndOnlySuccessIsAudited() throws Exception {
        TestIdentities.Employee employee =
                employeeWithPassword(TestIdentities.activeOrganization(jdbc));
        String typedEmail = "  " + employee.email().toUpperCase() + " ";

        mvc.perform(login(employee.organizationLoginKey(), employee.email(), "bad-" + PASSWORD))
                .andReturn();
        MvcResult success =
                mvc.perform(login(employee.organizationLoginKey(), typedEmail, PASSWORD))
                        .andReturn();
        assertThat(success.getResponse().getStatus()).isEqualTo(200);

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT email_attempted, success, host(ip) AS ip FROM login_attempt"
                                + " WHERE organization_login_key_attempted = ? ORDER BY id",
                        employee.organizationLoginKey());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("email_attempted", employee.email())
                .containsEntry("success", false)
                .containsEntry("ip", "127.0.0.1");
        assertThat(rows.get(1))
                .containsEntry("email_attempted", typedEmail)
                .containsEntry("success", true);

        List<Map<String, Object>> audit =
                jdbc.queryForList(
                        "SELECT actor_id, action, target_type, target_id, host(ip) AS ip,"
                                + " details ->> 'v' AS v,"
                                + " details -> 'attributes' ->> 'sessionId' AS session_id"
                                + " FROM audit_log WHERE organization_id = ?",
                        employee.organizationId());
        UUID family =
                jdbc.queryForObject(
                        "SELECT family_id FROM refresh_token WHERE employee_id = ?",
                        UUID.class,
                        employee.id());
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0))
                .containsEntry("actor_id", employee.id().toString())
                .containsEntry("action", "LOGIN_SUCCEEDED")
                .containsEntry("target_type", "EMPLOYEE")
                .containsEntry("target_id", employee.id().toString())
                .containsEntry("ip", "127.0.0.1")
                .containsEntry("session_id", family.toString());
    }

    // ---- tenant boundary ----

    @Test
    void theSameEmailInTwoOrganizationsIsTwoSeparateAccounts() throws Exception {
        TestIdentities.Organization orgA = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Organization orgB = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee inA = employeeWithPassword(orgA);
        String passwordB = "Other-" + UUID.randomUUID();
        UUID inB =
                jdbc.queryForObject(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, status, role) VALUES (?, ?, 'Jane B', ?, ?,"
                                + " 'ACTIVE', 'EMPLOYEE') RETURNING id",
                        UUID.class,
                        orgB.id(),
                        "E-" + UUID.randomUUID(),
                        inA.email(),
                        inA.email());
        jdbc.update(
                "UPDATE employee SET password_hash = ? WHERE id = ?",
                passwordHasher.hash(passwordB),
                inB);

        // Organization A with B's password: not an account.
        assertThat(
                        mvc.perform(login(orgA.loginKey(), inA.email(), passwordB))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);

        MvcResult intoB = mvc.perform(login(orgB.loginKey(), inA.email(), passwordB)).andReturn();
        assertThat(intoB.getResponse().getStatus()).isEqualTo(200);
        SignedJWT jwt = SignedJWT.parse((String) body(intoB).get("accessToken"));
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(inB.toString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("org")).isEqualTo(orgB.id().toString());

        MvcResult me =
                mvc.perform(
                                get("/api/v1/me")
                                        .header(
                                                "Authorization",
                                                "Bearer " + body(intoB).get("accessToken")))
                        .andReturn();
        assertThat(body(me)).containsEntry("id", inB.toString()).containsEntry("name", "Jane B");
        assertThat(me.getResponse().getContentAsString())
                .doesNotContain(orgA.loginKey())
                .doesNotContain(inA.id().toString());
    }

    // ---- input and cross-site checks ----

    @Test
    void blankFieldsAreAValidationErrorThatEchoesNothing() throws Exception {
        MvcResult result =
                mvc.perform(
                                post(LOGIN)
                                        .contentType("application/json")
                                        .content(
                                                "{\"organization\":\" \",\"email\":\"\","
                                                        + "\"password\":\"\"}"))
                        .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result))
                .containsEntry("type", "urn:peoplehub:problem:validation-error")
                .containsKey("fieldErrors");
        assertThat(result.getResponse().getContentAsString())
                .contains("organization")
                .contains("email")
                .contains("password");
    }

    @Test
    void aForeignOriginIsForbiddenAndTheAppOriginOrNoOriginIsAllowed() throws Exception {
        TestIdentities.Employee employee =
                employeeWithPassword(TestIdentities.activeOrganization(jdbc));

        MvcResult foreign =
                mvc.perform(
                                login(employee.organizationLoginKey(), employee.email(), PASSWORD)
                                        .header("Origin", "https://evil.example"))
                        .andReturn();
        assertThat(foreign.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(foreign))
                .containsEntry("type", "urn:peoplehub:problem:forbidden")
                .containsEntry("detail", "The request could not be verified.");
        assertThat(foreign.getResponse().getHeaders("Set-Cookie")).isEmpty();

        assertThat(
                        mvc.perform(
                                        login(
                                                        employee.organizationLoginKey(),
                                                        employee.email(),
                                                        PASSWORD)
                                                .header("Origin", APP_ORIGIN))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    @Test
    void theAppOriginMayCallTheApiWithCredentials() throws Exception {
        MvcResult preflight =
                mvc.perform(
                                options(LOGIN)
                                        .header("Origin", APP_ORIGIN)
                                        .header("Access-Control-Request-Method", "POST")
                                        .header("Access-Control-Request-Headers", "content-type"))
                        .andReturn();

        assertThat(preflight.getResponse().getStatus()).isEqualTo(200);
        assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Origin"))
                .isEqualTo(APP_ORIGIN);
        assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Credentials"))
                .isEqualTo("true");

        MvcResult foreign =
                mvc.perform(
                                options(LOGIN)
                                        .header("Origin", "https://evil.example")
                                        .header("Access-Control-Request-Method", "POST"))
                        .andReturn();
        assertThat(foreign.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        // Rejected with the standard problem body, not Spring's plain-text "Invalid CORS request".
        assertThat(foreign.getResponse().getStatus()).isEqualTo(403);
        assertThat(foreign.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(body(foreign))
                .containsEntry("type", "urn:peoplehub:problem:forbidden")
                .containsEntry("detail", "The request could not be verified.")
                .containsKey("correlationId");
    }

    @Test
    void aStaleBearerTokenDoesNotBlockLogin() throws Exception {
        TestIdentities.Employee employee =
                employeeWithPassword(TestIdentities.activeOrganization(jdbc));

        MvcResult result =
                mvc.perform(
                                login(employee.organizationLoginKey(), employee.email(), PASSWORD)
                                        .header("Authorization", "Bearer expired.or.garbage"))
                        .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    // ---- helpers: the real registration flow (b2-2) ----

    private String register(String orgName, String email) throws Exception {
        String response =
                mvc.perform(
                                post("/api/v1/public/organizations/register")
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "organizationName",
                                                                orgName,
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
                                                                true))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return (String)
                JSON.readValue(response, new TypeReference<Map<String, Object>>() {})
                        .get("organizationLoginKey");
    }

    private String registerAndVerify(String orgName, String email) throws Exception {
        String loginKey = register(orgName, email);
        String rawToken =
                jdbc.queryForObject(
                        "SELECT eo.payload -> 'attributes' ->> 'verificationCode' FROM email_outbox"
                                + " eo JOIN organization o ON o.id = eo.organization_id WHERE"
                                + " o.login_key_normalized = ?",
                        String.class,
                        loginKey);
        mvc.perform(
                        post("/api/v1/public/organizations/verify-email")
                                .contentType("application/json")
                                .content("{\"token\":\"" + rawToken + "\"}"))
                .andReturn();
        return loginKey;
    }
}
