package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.SignedJWT;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.TestIdentities;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/me/password} end to end (b2-5, B2-5/P9, R3, R5; Spec 8.2): the current
 * password is required, the new one follows the policy, the calling session stays signed in and
 * every other one ends, a wrong current password counts toward the lockout, and the change is
 * audited.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class ChangePasswordTest {

    private static final String CHANGE = "/api/v1/me/password";
    private static final String OLD_PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String NEW_PASSWORD = "Vq7#nR3tLm9@pZx2Kd";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @MockitoSpyBean private PasswordHasher passwordHasher;

    private TestIdentities.Employee employee() {
        return employee(TestIdentities.activeOrganization(jdbc));
    }

    private TestIdentities.Employee employee(TestIdentities.Organization org) {
        return TestIdentities.activeEmployee(
                jdbc, org, "EMPLOYEE", passwordHasher.hash(OLD_PASSWORD));
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

    /** Signs in and returns the access token. */
    private String session(TestIdentities.Employee employee) throws Exception {
        MvcResult result = login(employee, OLD_PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return (String) body(result).get("accessToken");
    }

    private MvcResult change(String accessToken, String current, String next, String confirm)
            throws Exception {
        return mvc.perform(
                        post(CHANGE)
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "currentPassword",
                                                        current,
                                                        "newPassword",
                                                        next,
                                                        "confirmPassword",
                                                        confirm))))
                .andReturn();
    }

    private MvcResult change(String accessToken, String current) throws Exception {
        return change(accessToken, current, NEW_PASSWORD, NEW_PASSWORD);
    }

    private int status(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private static UUID sessionId(String accessToken) throws Exception {
        return UUID.fromString(
                SignedJWT.parse(accessToken).getJWTClaimsSet().getStringClaim("sid"));
    }

    private int failedCount(TestIdentities.Employee employee) {
        return jdbc.queryForObject(
                "SELECT failed_login_count FROM employee WHERE id = ?",
                Integer.class,
                employee.id());
    }

    private Timestamp lockedUntil(TestIdentities.Employee employee) {
        return jdbc.queryForObject(
                "SELECT locked_until FROM employee WHERE id = ?", Timestamp.class, employee.id());
    }

    private List<Map<String, Object>> audits(UUID organizationId, String action) {
        return jdbc.queryForList(
                "SELECT actor_id, target_type, target_id, host(ip) AS ip, details::text AS details"
                        + " FROM audit_log WHERE organization_id = ? AND action = ? ORDER BY id",
                organizationId,
                action);
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

    @Test
    void theRightCurrentPasswordChangesItKeepsThisSessionAndEndsTheOthers() throws Exception {
        TestIdentities.Employee employee = employee();
        String current = session(employee);
        String other = session(employee);

        MvcResult result = change(current, OLD_PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(status(current)).isEqualTo(200);
        assertThat(status(other)).isEqualTo(401);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoked FROM refresh_token WHERE family_id = ?",
                                Boolean.class,
                                sessionId(current)))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoke_reason FROM refresh_token WHERE family_id = ?",
                                String.class,
                                sessionId(other)))
                .isEqualTo("PASSWORD_CHANGED");
        assertThat(login(employee, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(employee, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);

        List<Map<String, Object>> audits = audits(employee.organizationId(), "PASSWORD_CHANGED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .containsEntry("actor_id", employee.id().toString())
                .containsEntry("target_type", "EMPLOYEE")
                .containsEntry("target_id", employee.id().toString())
                .containsEntry("ip", "127.0.0.1");
        assertThat((String) audits.get(0).get("details"))
                .contains("\"revokedSessions\": 1")
                .doesNotContain(OLD_PASSWORD)
                .doesNotContain(NEW_PASSWORD);
    }

    @Test
    void aWrongCurrentPasswordIsAFieldErrorThatCountsTowardTheLockout() throws Exception {
        TestIdentities.Employee employee = employee();
        String current = session(employee);
        String other = session(employee);

        MvcResult result = change(current, "wrong-" + UUID.randomUUID());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(comparable(result))
                .containsEntry("type", "urn:peoplehub:problem:validation-error")
                .containsEntry(
                        "fieldErrors",
                        List.of(
                                Map.of(
                                        "field",
                                        "currentPassword",
                                        "message",
                                        PasswordChangeService.WRONG_CURRENT_PASSWORD)));
        // The failure is committed even though the request failed.
        assertThat(failedCount(employee)).isEqualTo(1);
        assertThat(status(other)).isEqualTo(200);
        assertThat(login(employee, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(audits(employee.organizationId(), "PASSWORD_CHANGED")).isEmpty();
    }

    @Test
    void fiveWrongCurrentPasswordsLockTheAccountAndLockedAttemptsAnswerTheSame() throws Exception {
        TestIdentities.Employee employee = employee();
        String current = session(employee);
        Map<String, Object> wrong = null;
        for (int i = 0; i < 5; i++) {
            wrong = comparable(change(current, "wrong-" + UUID.randomUUID()));
        }

        assertThat(failedCount(employee)).isEqualTo(5);
        Timestamp lockedUntil = lockedUntil(employee);
        assertThat(lockedUntil).isNotNull();
        List<Map<String, Object>> locks = audits(employee.organizationId(), "ACCOUNT_LOCKED");
        assertThat(locks).hasSize(1);
        assertThat(locks.get(0)).containsEntry("actor_id", employee.id().toString());

        // While locked, even the right current password fails the same way, still with exactly one
        // Argon2 check, and neither counts nor extends the lock.
        clearInvocations(passwordHasher);
        MvcResult whileLocked = change(current, OLD_PASSWORD);
        assertThat(whileLocked.getResponse().getStatus()).isEqualTo(400);
        assertThat(comparable(whileLocked)).isEqualTo(wrong);
        verify(passwordHasher, times(1)).matches(anyString(), anyString());
        assertThat(failedCount(employee)).isEqualTo(5);
        assertThat(lockedUntil(employee)).isEqualTo(lockedUntil);

        // After the lock ends the change works; it does not clear the failed sign-in count.
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(change(current, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(204);
        assertThat(failedCount(employee)).isEqualTo(5);
    }

    @Test
    void aRejectedNewPasswordChangesNothingAndIsNotCounted() throws Exception {
        TestIdentities.Employee employee = employee();
        String current = session(employee);

        MvcResult mismatch = change(current, OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD + "x");
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(400);
        assertThat(mismatch.getResponse().getContentAsString()).contains("confirmPassword");

        MvcResult tooShort = change(current, OLD_PASSWORD, "Short1!", "Short1!");
        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooShort.getResponse().getContentAsString())
                .contains("\"field\":\"newPassword\"")
                .doesNotContain("Short1!");

        MvcResult breached = change(current, OLD_PASSWORD, "01TeleMike01", "01TeleMike01");
        assertThat(breached.getResponse().getStatus()).isEqualTo(400);
        assertThat(breached.getResponse().getContentAsString()).contains("data breach");

        MvcResult blank =
                mvc.perform(
                                post(CHANGE)
                                        .header("Authorization", "Bearer " + current)
                                        .contentType("application/json")
                                        .content(
                                                "{\"currentPassword\":\"\",\"newPassword\":\" \","
                                                        + "\"confirmPassword\":\"\"}"))
                        .andReturn();
        assertThat(blank.getResponse().getStatus()).isEqualTo(400);
        assertThat(blank.getResponse().getContentAsString())
                .contains("currentPassword")
                .contains("newPassword")
                .contains("confirmPassword");

        assertThat(failedCount(employee)).isZero();
        assertThat(login(employee, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(audits(employee.organizationId(), "PASSWORD_CHANGED")).isEmpty();
    }

    @Test
    void withoutAnAccessTokenItIsA401AndNothingChanges() throws Exception {
        TestIdentities.Employee employee = employee();

        MvcResult result =
                mvc.perform(
                                post(CHANGE)
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "currentPassword",
                                                                OLD_PASSWORD,
                                                                "newPassword",
                                                                NEW_PASSWORD,
                                                                "confirmPassword",
                                                                NEW_PASSWORD))))
                        .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(login(employee, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theSameEmailInAnotherOrganizationIsNotAffected() throws Exception {
        TestIdentities.Employee inA = employee();
        TestIdentities.Organization orgB = TestIdentities.activeOrganization(jdbc);
        UUID inBId =
                jdbc.queryForObject(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, status, role, password_hash)"
                                + " VALUES (?, ?, 'Jane B', ?, ?, 'ACTIVE', 'EMPLOYEE', ?)"
                                + " RETURNING id",
                        UUID.class,
                        orgB.id(),
                        "E-" + UUID.randomUUID(),
                        inA.email(),
                        inA.email(),
                        passwordHasher.hash(OLD_PASSWORD));
        TestIdentities.Employee inB =
                new TestIdentities.Employee(
                        inBId, orgB.id(), orgB.loginKey(), inA.email(), "EMPLOYEE");
        String sessionB = session(inB);
        String sessionA = session(inA);

        change(sessionA, "wrong-" + UUID.randomUUID());
        assertThat(change(sessionA, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(204);

        assertThat(status(sessionB)).isEqualTo(200);
        assertThat(failedCount(inB)).isZero();
        assertThat(login(inB, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(audits(orgB.id(), "PASSWORD_CHANGED")).isEmpty();
    }

    @Test
    void neitherPasswordReachesTheLogs(CapturedOutput output) throws Exception {
        TestIdentities.Employee employee = employee();
        String current = session(employee);
        String wrong = "wrong-" + UUID.randomUUID();
        change(current, wrong);
        change(current, OLD_PASSWORD);

        String applicationLogs =
                output.getAll()
                        .lines()
                        .filter(line -> line.startsWith("{\"@timestamp\""))
                        .reduce("", (all, line) -> all + line + "\n");
        assertThat(applicationLogs)
                .contains("/api/v1/me/password")
                .doesNotContain(wrong)
                .doesNotContain(OLD_PASSWORD)
                .doesNotContain(NEW_PASSWORD)
                .doesNotContain(employee.email());
    }
}
