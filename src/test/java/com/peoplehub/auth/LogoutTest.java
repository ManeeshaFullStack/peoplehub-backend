package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code POST /api/v1/auth/logout} (b2-3, B2-3/9-B2-3/11, B2-3/14, B2-3/17): ends exactly the
 * current session, immediately, for both its refresh and its access tokens; is idempotent; and is
 * protected by the CSRF and Origin checks whenever there is a session to end.
 */
@IntegrationTest
@AutoConfigureMockMvc
class LogoutTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String LOGOUT = "/api/v1/auth/logout";

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;

    private AuthTestClient client;
    private TestIdentities.Employee employee;

    @BeforeEach
    void anActiveEmployee() {
        client = new AuthTestClient(mvc);
        employee =
                TestIdentities.activeEmployee(
                        jdbc,
                        TestIdentities.activeOrganization(jdbc),
                        "EMPLOYEE",
                        passwordHasher.hash(PASSWORD));
    }

    private List<Map<String, Object>> auditRows(String action) {
        return jdbc.queryForList(
                "SELECT actor_id, target_id, host(ip) AS ip,"
                        + " details -> 'attributes' ->> 'sessionId' AS session_id"
                        + " FROM audit_log WHERE organization_id = ? AND action = ?",
                employee.organizationId(),
                action);
    }

    private static void assertCookiesCleared(MvcResult result) {
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .hasSize(2)
                .anySatisfy(c -> assertThat(c).startsWith("__Secure-peoplehub_rt=;"))
                .anySatisfy(c -> assertThat(c).startsWith("__Secure-peoplehub_csrf=;"))
                .allSatisfy(c -> assertThat(c).contains("Max-Age=0").contains("Path=/api/v1/auth"));
    }

    @Test
    void logoutEndsTheSessionImmediatelyAndIsAudited() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);
        assertThat(client.meStatus(session)).isEqualTo(200);

        MvcResult result = client.logout(session);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertCookiesCleared(result);

        List<Map<String, Object>> tokens =
                jdbc.queryForList(
                        "SELECT family_id, revoked, revoke_reason FROM refresh_token"
                                + " WHERE employee_id = ?",
                        employee.id());
        assertThat(tokens)
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(row)
                                        .containsEntry("revoked", true)
                                        .containsEntry("revoke_reason", "LOGOUT"));

        // The access token stops working now, not in up to 15 minutes (B2-3/14).
        assertThat(client.meStatus(session)).isEqualTo(401);
        // The refresh token is dead too.
        assertThat(client.refresh(session).getResponse().getStatus()).isEqualTo(401);

        assertThat(auditRows("LOGOUT"))
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(row)
                                        .containsEntry("actor_id", employee.id().toString())
                                        .containsEntry("target_id", employee.id().toString())
                                        .containsEntry("ip", "127.0.0.1")
                                        .containsEntry(
                                                "session_id",
                                                tokens.get(0).get("family_id").toString()));
    }

    @Test
    void logoutEndsOnlyTheCurrentSession() throws Exception {
        AuthTestClient.Session laptop = client.login(employee, PASSWORD);
        AuthTestClient.Session phone = client.login(employee, PASSWORD);

        assertThat(client.logout(laptop).getResponse().getStatus()).isEqualTo(204);

        assertThat(client.meStatus(laptop)).isEqualTo(401);
        assertThat(client.meStatus(phone)).isEqualTo(200);
        assertThat(client.refresh(phone).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void logoutIsIdempotent() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);

        assertThat(client.logout(session).getResponse().getStatus()).isEqualTo(204);
        MvcResult again = client.logout(session);

        assertThat(again.getResponse().getStatus()).isEqualTo(204);
        assertCookiesCleared(again);
        assertThat(auditRows("LOGOUT")).hasSize(1);
    }

    @Test
    void logoutWithoutAnySessionStillSucceeds() throws Exception {
        MvcResult noCookies = mvc.perform(post(LOGOUT)).andReturn();
        assertThat(noCookies.getResponse().getStatus()).isEqualTo(204);
        assertCookiesCleared(noCookies);

        MvcResult unknownToken =
                client.logout(
                        new AuthTestClient.Session(
                                "unused",
                                "unknown-" + UUID.randomUUID(),
                                "csrf-" + UUID.randomUUID()));
        assertThat(unknownToken.getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void aLogoutWithoutTheCsrfTokenIsForbiddenAndEndsNothing() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);

        MvcResult result =
                mvc.perform(
                                post(LOGOUT)
                                        .cookie(
                                                new Cookie(
                                                        AuthCookies.REFRESH_COOKIE,
                                                        session.refreshToken()),
                                                new Cookie(
                                                        AuthCookies.CSRF_COOKIE,
                                                        session.csrfToken())))
                        .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(AuthTestClient.body(result))
                .containsEntry("type", "urn:peoplehub:problem:forbidden")
                .containsEntry("detail", "The request could not be verified.");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(client.meStatus(session)).isEqualTo(200);
        assertThat(auditRows("LOGOUT")).isEmpty();
    }

    @Test
    void aLogoutFromAForeignOriginIsForbiddenAndEndsNothing() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);

        MvcResult result =
                mvc.perform(
                                session.withCookies(post(LOGOUT))
                                        .header("Origin", "https://evil.example"))
                        .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(client.meStatus(session)).isEqualTo(200);
    }

    @Test
    void presentingTheRefreshTokenAfterLogoutIsTreatedAsReuse() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);
        client.logout(session);

        // A browser that logged out no longer has the cookie; whoever still presents it copied it.
        assertThat(client.refresh(session).getResponse().getStatus()).isEqualTo(401);
        assertThat(auditRows("REFRESH_TOKEN_REUSE_DETECTED")).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoke_reason FROM refresh_token WHERE employee_id = ?",
                                String.class,
                                employee.id()))
                .isEqualTo("LOGOUT");
    }
}
