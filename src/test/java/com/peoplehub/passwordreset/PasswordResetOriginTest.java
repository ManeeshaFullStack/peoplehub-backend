package com.peoplehub.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestIdentities;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Origin check on forgot and reset password (b2-5, B2-5 R6): the same check as login. With an
 * app origin configured, a request from any other origin is a 403 and does nothing; the app origin,
 * or no {@code Origin} header at all (a non-browser client), is allowed.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = "peoplehub.security.app-origin=" + PasswordResetOriginTest.APP_ORIGIN)
class PasswordResetOriginTest {

    static final String APP_ORIGIN = "https://app.peoplehub.test";

    private static final String FOREIGN_ORIGIN = "https://evil.example";
    private static final String OLD_PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String NEW_PASSWORD = "Vq7#nR3tLm9@pZx2Kd";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;

    private TestIdentities.Employee employee() {
        return TestIdentities.activeEmployee(
                jdbc,
                TestIdentities.activeOrganization(jdbc),
                "EMPLOYEE",
                passwordHasher.hash(OLD_PASSWORD));
    }

    private static MockHttpServletRequestBuilder forgot(TestIdentities.Employee employee) {
        return post("/api/v1/auth/forgot-password")
                .contentType("application/json")
                .content(
                        JSON.writeValueAsString(
                                Map.of(
                                        "organization",
                                        employee.organizationLoginKey(),
                                        "email",
                                        employee.email())));
    }

    private static MockHttpServletRequestBuilder reset(String code) {
        return post("/api/v1/auth/reset-password")
                .contentType("application/json")
                .content(
                        JSON.writeValueAsString(
                                Map.of(
                                        "token",
                                        code,
                                        "password",
                                        NEW_PASSWORD,
                                        "confirmPassword",
                                        NEW_PASSWORD)));
    }

    private List<String> emailedCodes(TestIdentities.Employee employee) {
        return jdbc.queryForList(
                "SELECT payload -> 'attributes' ->> 'resetCode' FROM email_outbox"
                        + " WHERE organization_id = ? AND type = 'PASSWORD_RESET'",
                String.class,
                employee.organizationId());
    }

    private void assertForbidden(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        Map<String, Object> body =
                JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
        assertThat(body)
                .containsEntry("type", "urn:peoplehub:problem:forbidden")
                .containsEntry("detail", "The request could not be verified.");
    }

    private boolean passwordIs(TestIdentities.Employee employee, String password) {
        return passwordHasher.matches(
                password,
                jdbc.queryForObject(
                        "SELECT password_hash FROM employee WHERE id = ?",
                        String.class,
                        employee.id()));
    }

    @Test
    void forgotPasswordFromAForeignOriginIsForbiddenAndSendsNothing() throws Exception {
        TestIdentities.Employee employee = employee();

        assertForbidden(mvc.perform(forgot(employee).header("Origin", FOREIGN_ORIGIN)).andReturn());

        assertThat(emailedCodes(employee)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM password_reset_token WHERE employee_id = ?",
                                Integer.class,
                                employee.id()))
                .isZero();
    }

    @Test
    void resetPasswordFromAForeignOriginIsForbiddenAndLeavesTheCodeUsable() throws Exception {
        TestIdentities.Employee employee = employee();
        mvc.perform(forgot(employee).header("Origin", APP_ORIGIN)).andReturn();
        String code = emailedCodes(employee).get(0);

        assertForbidden(mvc.perform(reset(code).header("Origin", FOREIGN_ORIGIN)).andReturn());

        assertThat(passwordIs(employee, OLD_PASSWORD)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT consumed_at IS NULL FROM password_reset_token"
                                        + " WHERE employee_id = ?",
                                Boolean.class,
                                employee.id()))
                .isTrue();

        assertThat(
                        mvc.perform(reset(code).header("Origin", APP_ORIGIN))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        assertThat(passwordIs(employee, NEW_PASSWORD)).isTrue();
    }

    @Test
    void theAppOriginAndNoOriginAreBothAllowed() throws Exception {
        TestIdentities.Employee fromApp = employee();
        TestIdentities.Employee withoutOrigin = employee();

        assertThat(
                        mvc.perform(forgot(fromApp).header("Origin", APP_ORIGIN))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(202);
        assertThat(mvc.perform(forgot(withoutOrigin)).andReturn().getResponse().getStatus())
                .isEqualTo(202);
        assertThat(emailedCodes(fromApp)).hasSize(1);
        assertThat(emailedCodes(withoutOrigin)).hasSize(1);

        assertThat(
                        mvc.perform(reset(emailedCodes(withoutOrigin).get(0)))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        assertThat(passwordIs(withoutOrigin, NEW_PASSWORD)).isTrue();
    }
}
