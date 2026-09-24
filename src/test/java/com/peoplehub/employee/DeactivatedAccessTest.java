package com.peoplehub.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestIdentities;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A deactivated Employee, Admin or Super Admin has no way in (b2-6, D26; Spec 22.1 "cannot log in,
 * refresh, use an existing session"): not their existing access token, not a refresh, not a new
 * login, not their sessions list, not a password reset. After reactivation, nothing of the old
 * session works either; they sign in again.
 */
@IntegrationTest
@AutoConfigureMockMvc
class DeactivatedAccessTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String NEW_PASSWORD = "Vq7#nR3tLm9@pZx2Kd";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;

    private record Session(String accessToken, String refreshToken, String csrf) {}

    private TestIdentities.Employee create(TestIdentities.Organization org, String role) {
        return TestIdentities.activeEmployee(jdbc, org, role, passwordHasher.hash(PASSWORD));
    }

    private MvcResult login(TestIdentities.Employee employee, String password) throws Exception {
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
                                                        password))))
                .andReturn();
    }

    private Session signIn(TestIdentities.Employee employee) throws Exception {
        MvcResult result = login(employee, PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> body = body(result);
        return new Session(
                (String) body.get("accessToken"),
                result.getResponse().getCookie("__Secure-peoplehub_rt").getValue(),
                (String) body.get("csrfToken"));
    }

    private int status(Session session, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + session.accessToken()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private MvcResult refresh(Session session) throws Exception {
        return mvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(
                                        new Cookie("__Secure-peoplehub_rt", session.refreshToken()),
                                        new Cookie("__Secure-peoplehub_csrf", session.csrf()))
                                .header("X-CSRF-Token", session.csrf()))
                .andReturn();
    }

    private void forgot(TestIdentities.Employee employee) throws Exception {
        mvc.perform(
                        post("/api/v1/auth/forgot-password")
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "organization",
                                                        employee.organizationLoginKey(),
                                                        "email",
                                                        employee.email()))))
                .andReturn();
    }

    private int reset(String code) throws Exception {
        return mvc.perform(
                        post("/api/v1/auth/reset-password")
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "token",
                                                        code,
                                                        "password",
                                                        NEW_PASSWORD,
                                                        "confirmPassword",
                                                        NEW_PASSWORD))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private List<String> resetCodes(TestIdentities.Employee employee) {
        return jdbc.queryForList(
                "SELECT payload -> 'attributes' ->> 'resetCode' FROM email_outbox"
                        + " WHERE recipient = ? AND type = 'PASSWORD_RESET'",
                String.class,
                employee.email());
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private static Map<String, Object> comparable(MvcResult result) throws Exception {
        Map<String, Object> body = body(result);
        body.remove("instance");
        body.remove("correlationId");
        return body;
    }

    @ParameterizedTest(name = "a deactivated {0}")
    @ValueSource(strings = {"EMPLOYEE", "ADMIN", "SUPER_ADMIN"})
    void hasNoWayInAndMustSignInAgainAfterReactivation(String role) throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Session superAdmin = signIn(create(org, "SUPER_ADMIN"));
        TestIdentities.Employee person = create(org, role);
        Session session = signIn(person);
        forgot(person);
        String code = resetCodes(person).get(0);
        MvcResult wrongPassword = login(person, PASSWORD + "-wrong");

        assertThat(
                        status(
                                superAdmin,
                                post("/api/v1/admin/employees/" + person.id() + "/deactivate")))
                .isEqualTo(204);

        // The existing access token, on any endpoint, including their own sessions.
        assertThat(status(session, get("/api/v1/me"))).isEqualTo(401);
        assertThat(status(session, get("/api/v1/me/sessions"))).isEqualTo(401);
        assertThat(status(session, post("/api/v1/me/sessions/revoke-others"))).isEqualTo(401);
        assertThat(status(session, delete("/api/v1/me/sessions/" + UUID.randomUUID())))
                .isEqualTo(401);
        // A refresh, and no false theft alarm.
        MvcResult refreshed = refresh(session);
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(401);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_log WHERE organization_id = ?"
                                        + " AND action = 'REFRESH_TOKEN_REUSE_DETECTED'",
                                Integer.class,
                                org.id()))
                .isZero();
        // A new login, with the right password, looks exactly like a wrong one.
        MvcResult rightPassword = login(person, PASSWORD);
        assertThat(rightPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(comparable(rightPassword)).isEqualTo(comparable(wrongPassword));
        // A password reset: the old code is dead and no new one is sent.
        assertThat(reset(code)).isEqualTo(400);
        jdbc.update(
                "UPDATE password_reset_token SET created_at = created_at - interval '1 day'"
                        + " WHERE employee_id = ?",
                person.id());
        forgot(person);
        assertThat(resetCodes(person)).hasSize(1);

        assertThat(
                        status(
                                superAdmin,
                                post("/api/v1/admin/employees/" + person.id() + "/reactivate")))
                .isEqualTo(204);

        // Nothing of the old session comes back; a new sign-in works.
        assertThat(status(session, get("/api/v1/me"))).isEqualTo(401);
        assertThat(refresh(session).getResponse().getStatus()).isEqualTo(401);
        assertThat(reset(code)).isEqualTo(400);
        Session fresh = signIn(person);
        assertThat(status(fresh, get("/api/v1/me"))).isEqualTo(200);
        assertThat(status(fresh, get("/api/v1/me/sessions"))).isEqualTo(200);
    }
}
