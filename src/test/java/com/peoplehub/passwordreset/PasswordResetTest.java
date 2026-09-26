package com.peoplehub.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Forgot and reset password end to end (b2-5, B2-5/P6-P8, P11-P13; Spec 8.2, 13.0, 15.1): one
 * identical public answer, codes stored only as hashes, the throttle, single use and expiry, the
 * password policy, lockout clearing, every session ended, audit rows, and the tenant boundary.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetTest {

    private static final String FORGOT = "/api/v1/auth/forgot-password";
    private static final String RESET = "/api/v1/auth/reset-password";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String OLD_PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String NEW_PASSWORD = "Vq7#nR3tLm9@pZx2Kd";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private PasswordResetService service;

    // ---- helpers ----

    private TestIdentities.Employee employee() {
        return employee(TestIdentities.activeOrganization(jdbc));
    }

    private TestIdentities.Employee employee(TestIdentities.Organization org) {
        return TestIdentities.activeEmployee(
                jdbc, org, "EMPLOYEE", passwordHasher.hash(OLD_PASSWORD));
    }

    private MvcResult forgot(String organization, String email) throws Exception {
        return mvc.perform(
                        post(FORGOT)
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "organization",
                                                        organization,
                                                        "email",
                                                        email))))
                .andReturn();
    }

    private MvcResult forgot(TestIdentities.Employee employee) throws Exception {
        return forgot(employee.organizationLoginKey(), employee.email());
    }

    private MvcResult reset(String token, String password, String confirm) throws Exception {
        return mvc.perform(
                        post(RESET)
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "token",
                                                        token,
                                                        "password",
                                                        password,
                                                        "confirmPassword",
                                                        confirm))))
                .andReturn();
    }

    private MvcResult reset(String token) throws Exception {
        return reset(token, NEW_PASSWORD, NEW_PASSWORD);
    }

    private MvcResult login(TestIdentities.Employee employee, String password) throws Exception {
        return mvc.perform(
                        post(LOGIN)
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

    /** The reset codes emailed to this employee, oldest first. */
    private List<String> emailedCodes(TestIdentities.Employee employee) {
        return jdbc.queryForList(
                "SELECT payload -> 'attributes' ->> 'resetCode' FROM email_outbox"
                        + " WHERE organization_id = ? AND recipient = ? AND type = 'PASSWORD_RESET'"
                        + " ORDER BY created_at",
                String.class,
                employee.organizationId(),
                employee.email());
    }

    private String latestCode(TestIdentities.Employee employee) {
        List<String> codes = emailedCodes(employee);
        assertThat(codes).isNotEmpty();
        return codes.get(codes.size() - 1);
    }

    private int resetRows(TestIdentities.Employee employee) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM password_reset_token WHERE employee_id = ?",
                Integer.class,
                employee.id());
    }

    /** Moves this employee's reset requests back in time, as if they had been made earlier. */
    private void ageResetRequests(TestIdentities.Employee employee, Duration by) {
        jdbc.update(
                "UPDATE password_reset_token SET created_at = created_at - make_interval(secs =>"
                        + " ?) WHERE employee_id = ?",
                by.toSeconds(),
                employee.id());
    }

    private List<Map<String, Object>> audits(UUID organizationId, String action) {
        return jdbc.queryForList(
                "SELECT actor_id, target_type, target_id, host(ip) AS ip, details::text AS details"
                        + " FROM audit_log WHERE organization_id = ? AND action = ? ORDER BY id",
                organizationId,
                action);
    }

    /** The attributes of the single audit row of this action, parsed. */
    private Map<String, Object> auditAttributes(UUID organizationId, String action) {
        String attributes =
                jdbc.queryForObject(
                        "SELECT details -> 'attributes' FROM audit_log"
                                + " WHERE organization_id = ? AND action = ?",
                        String.class,
                        organizationId,
                        action);
        return JSON.readValue(attributes, new TypeReference<>() {});
    }

    private UUID resetRowId(TestIdentities.Employee employee) {
        return jdbc.queryForObject(
                "SELECT id FROM password_reset_token WHERE employee_id = ?",
                UUID.class,
                employee.id());
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private static Map<String, Object> comparable(MvcResult result) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(body(result));
        body.remove("instance");
        body.remove("correlationId");
        return body;
    }

    // ---- forgot password ----

    @Test
    void aKnownActiveAccountGetsOneCodeStoredOnlyAsAHash() throws Exception {
        TestIdentities.Employee employee = employee();

        MvcResult result = forgot(employee);

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(body(result))
                .containsOnlyKeys("message")
                .containsEntry("message", PasswordResetService.FORGOT_MESSAGE);
        String code = latestCode(employee);
        assertThat(code).matches("[0-9a-f]{64}");
        assertThat(resetRows(employee)).isEqualTo(1);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT token_hash, organization_id, expires_at FROM password_reset_token"
                                + " WHERE employee_id = ?",
                        employee.id());
        assertThat(row.get("token_hash")).isNotEqualTo(code);
        assertThat(row.get("organization_id")).isEqualTo(employee.organizationId());
        assertThat(((Timestamp) row.get("expires_at")).toInstant())
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM password_reset_token WHERE token_hash = ?",
                                Integer.class,
                                code))
                .isZero();

        Map<String, Object> email =
                jdbc.queryForMap(
                        "SELECT payload -> 'attributes' ->> 'organizationLoginKey' AS login_key,"
                                + " payload -> 'attributes' ->> 'firstName' AS first_name,"
                                + " payload -> 'attributes' ->> 'expiryMinutes' AS expiry"
                                + " FROM email_outbox WHERE recipient = ? AND type = 'PASSWORD_RESET'",
                        employee.email());
        assertThat(email)
                .containsEntry("login_key", employee.organizationLoginKey())
                .containsEntry("first_name", "Jane")
                .containsEntry("expiry", "30");

        List<Map<String, Object>> audits =
                audits(employee.organizationId(), "PASSWORD_RESET_REQUESTED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .containsEntry("actor_id", "anonymous")
                .containsEntry("target_type", "EMPLOYEE")
                .containsEntry("target_id", employee.id().toString())
                .containsEntry("ip", "127.0.0.1");
        assertThat((String) audits.get(0).get("details"))
                .doesNotContain(code)
                .doesNotContain(employee.email());
        assertThat(auditAttributes(employee.organizationId(), "PASSWORD_RESET_REQUESTED"))
                .containsOnlyKeys("requestId")
                .containsEntry("requestId", resetRowId(employee).toString());
    }

    @Test
    void everyOtherCaseGetsTheIdenticalAnswerAndNothingHappens() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee active = employee(org);
        TestIdentities.Employee deactivated = employee(org);
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", deactivated.id());
        TestIdentities.Employee invited = employee(org);
        jdbc.update("UPDATE employee SET status = 'INVITED' WHERE id = ?", invited.id());
        TestIdentities.Organization suspended = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee inSuspended = employee(suspended);
        jdbc.update("UPDATE organization SET status = 'SUSPENDED' WHERE id = ?", suspended.id());
        TestIdentities.Organization pending = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee unverified = employee(pending);
        jdbc.update(
                "UPDATE organization SET status = 'PENDING_VERIFICATION' WHERE id = ?",
                pending.id());
        jdbc.update(
                "UPDATE employee SET status = 'PENDING_VERIFICATION' WHERE id = ?",
                unverified.id());

        Map<String, String[]> attempts = new LinkedHashMap<>();
        attempts.put("active account", new String[] {org.loginKey(), active.email()});
        attempts.put(
                "unknown organization",
                new String[] {"no-such-org-" + UUID.randomUUID(), active.email()});
        attempts.put(
                "unknown email",
                new String[] {org.loginKey(), "nobody-" + UUID.randomUUID() + "@example.com"});
        attempts.put("deactivated employee", new String[] {org.loginKey(), deactivated.email()});
        attempts.put("invited employee", new String[] {org.loginKey(), invited.email()});
        attempts.put(
                "suspended organization", new String[] {suspended.loginKey(), inSuspended.email()});
        attempts.put("unverified founder", new String[] {pending.loginKey(), unverified.email()});
        attempts.put("no letter or digit", new String[] {"---", active.email()});

        String first = null;
        for (Map.Entry<String, String[]> attempt : attempts.entrySet()) {
            MvcResult result = forgot(attempt.getValue()[0], attempt.getValue()[1]);
            assertThat(result.getResponse().getStatus()).as(attempt.getKey()).isEqualTo(202);
            String content = result.getResponse().getContentAsString();
            if (first == null) {
                first = content;
            }
            assertThat(content).as(attempt.getKey()).isEqualTo(first);
        }

        assertThat(resetRows(active)).isEqualTo(1);
        for (TestIdentities.Employee other :
                List.of(deactivated, invited, inSuspended, unverified)) {
            assertThat(resetRows(other)).isZero();
            assertThat(emailedCodes(other)).isEmpty();
        }
        for (UUID orgId : List.of(suspended.id(), pending.id())) {
            assertThat(audits(orgId, "PASSWORD_RESET_REQUESTED")).isEmpty();
        }
    }

    @Test
    void aSecondRequestWithinFiveMinutesSendsNothingAndTheSameAnswer() throws Exception {
        TestIdentities.Employee employee = employee();
        String first = forgot(employee).getResponse().getContentAsString();

        MvcResult second = forgot(employee);

        assertThat(second.getResponse().getStatus()).isEqualTo(202);
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first);
        assertThat(resetRows(employee)).isEqualTo(1);
        assertThat(emailedCodes(employee)).hasSize(1);
        assertThat(audits(employee.organizationId(), "PASSWORD_RESET_REQUESTED")).hasSize(1);
    }

    @Test
    void afterFiveMinutesANewCodeReplacesTheOldOne() throws Exception {
        TestIdentities.Employee employee = employee();
        forgot(employee);
        String oldCode = latestCode(employee);
        ageResetRequests(employee, Duration.ofMinutes(5).plusSeconds(1));

        forgot(employee);

        List<String> codes = emailedCodes(employee);
        assertThat(codes).hasSize(2);
        assertThat(codes.get(1)).isNotEqualTo(oldCode);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM password_reset_token WHERE employee_id = ?"
                                        + " AND invalidated_at IS NOT NULL",
                                Integer.class,
                                employee.id()))
                .isEqualTo(1);

        MvcResult withOld = reset(oldCode);
        assertThat(withOld.getResponse().getStatus()).isEqualTo(400);
        assertThat(reset(codes.get(1)).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void atMostFiveRequestsAreSentInAnyTwentyFourHours() throws Exception {
        TestIdentities.Employee employee = employee();
        for (int i = 0; i < 5; i++) {
            forgot(employee);
            ageResetRequests(employee, Duration.ofMinutes(6));
        }
        assertThat(resetRows(employee)).isEqualTo(5);

        forgot(employee);
        assertThat(resetRows(employee)).isEqualTo(5);

        // Once the oldest request is more than a day old, one more is allowed.
        ageResetRequests(employee, Duration.ofHours(24).minusMinutes(25));
        forgot(employee);
        assertThat(resetRows(employee)).isEqualTo(6);
        assertThat(emailedCodes(employee)).hasSize(6);
    }

    @Test
    void theSameEmailInAnotherOrganizationIsASeparateAccount() throws Exception {
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

        forgot(inA);
        assertThat(reset(latestCode(inA)).getResponse().getStatus()).isEqualTo(204);
        // No sessions and no lock: both are recorded as such.
        assertThat(auditAttributes(inA.organizationId(), "PASSWORD_RESET_COMPLETED"))
                .containsEntry("revokedSessions", 0)
                .containsEntry("lockoutCleared", false);

        assertThat(resetRows(inB)).isZero();
        assertThat(audits(orgB.id(), "PASSWORD_RESET_REQUESTED")).isEmpty();
        assertThat(audits(orgB.id(), "PASSWORD_RESET_COMPLETED")).isEmpty();
        assertThat(login(inB, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(inA, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- reset password ----

    @Test
    void aResetSetsThePasswordEndsEverySessionClearsTheLockoutAndIsAudited() throws Exception {
        TestIdentities.Employee employee = employee();
        MvcResult session = login(employee, OLD_PASSWORD);
        String accessToken = (String) body(session).get("accessToken");
        login(employee, OLD_PASSWORD);
        for (int i = 0; i < 5; i++) {
            login(employee, "wrong-" + UUID.randomUUID());
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT locked_until FROM employee WHERE id = ?",
                                Timestamp.class,
                                employee.id()))
                .isNotNull();
        forgot(employee);
        String code = latestCode(employee);

        MvcResult result = reset(code);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        // The old password no longer works, the new one does, and the lockout is gone.
        Map<String, Object> state =
                jdbc.queryForMap(
                        "SELECT failed_login_count, locked_until FROM employee WHERE id = ?",
                        employee.id());
        assertThat(state)
                .containsEntry("failed_login_count", 0)
                .containsEntry("locked_until", null);
        // Every session ended, and with it the access token issued before the reset.
        assertThat(
                        jdbc.queryForList(
                                "SELECT revoke_reason FROM refresh_token WHERE employee_id = ?",
                                String.class,
                                employee.id()))
                .hasSize(2)
                .containsOnly("PASSWORD_RESET");
        assertThat(
                        mvc.perform(
                                        get("/api/v1/me")
                                                .header("Authorization", "Bearer " + accessToken))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        assertThat(login(employee, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(employee, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT consumed_at FROM password_reset_token WHERE employee_id = ?",
                                Timestamp.class,
                                employee.id()))
                .isNotNull();

        List<Map<String, Object>> audits =
                audits(employee.organizationId(), "PASSWORD_RESET_COMPLETED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .containsEntry("actor_id", employee.id().toString())
                .containsEntry("target_type", "EMPLOYEE")
                .containsEntry("target_id", employee.id().toString())
                .containsEntry("ip", "127.0.0.1");
        assertThat((String) audits.get(0).get("details"))
                .doesNotContain(code)
                .doesNotContain(NEW_PASSWORD);
        // Two sessions ended, and the reset ended an active lock.
        assertThat(auditAttributes(employee.organizationId(), "PASSWORD_RESET_COMPLETED"))
                .containsOnlyKeys("requestId", "revokedSessions", "lockoutCleared")
                .containsEntry("requestId", resetRowId(employee).toString())
                .containsEntry("revokedSessions", 2)
                .containsEntry("lockoutCleared", true);
    }

    @Test
    void anUnusableCodeIsOneGenericValidationError() throws Exception {
        TestIdentities.Employee used = employee();
        forgot(used);
        String usedCode = latestCode(used);
        assertThat(reset(usedCode).getResponse().getStatus()).isEqualTo(204);

        TestIdentities.Employee expiring = employee();
        forgot(expiring);
        String expiredCode = latestCode(expiring);

        TestIdentities.Employee deactivated = employee();
        forgot(deactivated);
        String deactivatedCode = latestCode(deactivated);
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", deactivated.id());

        clock.advance(Duration.ofMinutes(30).plusSeconds(1));

        Map<String, String> codes = new LinkedHashMap<>();
        codes.put("already used", usedCode);
        codes.put("expired", expiredCode);
        codes.put("account deactivated", deactivatedCode);
        codes.put("unknown", "0".repeat(64));
        codes.put("far too long", "a".repeat(256));

        Map<String, Object> first = null;
        for (Map.Entry<String, String> code : codes.entrySet()) {
            MvcResult result = reset(code.getValue());
            assertThat(result.getResponse().getStatus()).as(code.getKey()).isEqualTo(400);
            Map<String, Object> body = comparable(result);
            if (first == null) {
                first = body;
            }
            assertThat(body).as(code.getKey()).isEqualTo(first);
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(code.getValue())
                    .doesNotContain(NEW_PASSWORD);
        }
        assertThat(first)
                .containsEntry("type", "urn:peoplehub:problem:validation-error")
                .containsEntry(
                        "fieldErrors",
                        List.of(
                                Map.of(
                                        "field",
                                        "token",
                                        "message",
                                        PasswordResetService.INVALID_CODE)));

        // Nothing changed for the expired and deactivated accounts.
        assertThat(login(expiring, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(audits(expiring.organizationId(), "PASSWORD_RESET_COMPLETED")).isEmpty();
        assertThat(audits(deactivated.organizationId(), "PASSWORD_RESET_COMPLETED")).isEmpty();
    }

    @Test
    void aRejectedPasswordLeavesTheCodeUsable() throws Exception {
        TestIdentities.Employee employee = employee();
        forgot(employee);
        String code = latestCode(employee);

        MvcResult mismatch = reset(code, NEW_PASSWORD, NEW_PASSWORD + "x");
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(400);
        assertThat(mismatch.getResponse().getContentAsString()).contains("confirmPassword");

        MvcResult tooShort = reset(code, "Short1!", "Short1!");
        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooShort.getResponse().getContentAsString())
                .contains("\"field\":\"password\"")
                .doesNotContain("Short1!");

        MvcResult breached = reset(code, "01TeleMike01", "01TeleMike01");
        assertThat(breached.getResponse().getStatus()).isEqualTo(400);
        assertThat(breached.getResponse().getContentAsString()).contains("data breach");

        assertThat(login(employee, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(reset(code).getResponse().getStatus()).isEqualTo(204);
    }

    /**
     * No app origin is configured in this context, so CORS has nothing to check and the Origin
     * guard alone refuses any browser request (B2-5 R6). {@code PasswordResetOriginTest} covers a
     * configured app origin.
     */
    @Test
    void withNoAppOriginConfiguredAnyBrowserOriginIsForbiddenAndNothingHappens() throws Exception {
        TestIdentities.Employee employee = employee();
        forgot(employee);
        String code = latestCode(employee);

        MvcResult forgotFromBrowser =
                mvc.perform(
                                post(FORGOT)
                                        .header("Origin", "https://app.peoplehub.test")
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "organization",
                                                                employee.organizationLoginKey(),
                                                                "email",
                                                                employee.email()))))
                        .andReturn();
        MvcResult resetFromBrowser =
                mvc.perform(
                                post(RESET)
                                        .header("Origin", "https://app.peoplehub.test")
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
                        .andReturn();

        for (MvcResult result : List.of(forgotFromBrowser, resetFromBrowser)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(result))
                    .containsEntry("type", "urn:peoplehub:problem:forbidden")
                    .containsEntry("detail", "The request could not be verified.");
        }
        ageResetRequests(employee, Duration.ofMinutes(6));
        assertThat(resetRows(employee)).isEqualTo(1);
        assertThat(login(employee, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(reset(code).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void blankFieldsAreAValidationErrorThatEchoesNothing() throws Exception {
        MvcResult forgot = forgot(" ", "");
        assertThat(forgot.getResponse().getStatus()).isEqualTo(400);
        assertThat(forgot.getResponse().getContentAsString())
                .contains("organization")
                .contains("email");

        MvcResult reset = reset("", "", "");
        assertThat(reset.getResponse().getStatus()).isEqualTo(400);
        assertThat(reset.getResponse().getContentAsString())
                .contains("token")
                .contains("password")
                .contains("confirmPassword");
    }

    @Test
    void twoParallelResetsWithOneCodeSucceedOnlyOnce() throws Exception {
        TestIdentities.Employee employee = employee();
        forgot(employee);
        ResetPasswordRequest request =
                new ResetPasswordRequest(latestCode(employee), NEW_PASSWORD, NEW_PASSWORD);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        int succeeded = 0;
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                attempts.add(
                        () -> {
                            try {
                                service.reset(request, null);
                                return true;
                            } catch (RuntimeException e) {
                                return false;
                            }
                        });
            }
            for (Future<Boolean> attempt : pool.invokeAll(attempts)) {
                if (attempt.get()) {
                    succeeded++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(audits(employee.organizationId(), "PASSWORD_RESET_COMPLETED")).hasSize(1);
    }

    @Test
    void neitherTheCodeNorThePasswordNorTheEmailReachesTheLogs(CapturedOutput output)
            throws Exception {
        TestIdentities.Employee employee = employee();
        forgot(employee);
        String code = latestCode(employee);
        reset(code, "Short1!", "Short1!");
        reset(code);

        String applicationLogs =
                output.getAll()
                        .lines()
                        .filter(line -> line.startsWith("{\"@timestamp\""))
                        .reduce("", (all, line) -> all + line + "\n");
        assertThat(applicationLogs)
                .contains("/api/v1/auth/reset-password")
                .doesNotContain(code)
                .doesNotContain(NEW_PASSWORD)
                .doesNotContain("Short1!")
                .doesNotContain(employee.email());
    }

    @Test
    void theExpiryFollowsTheApplicationClock() throws Exception {
        TestIdentities.Employee employee = employee();
        Instant issuedAt = clock.instant();
        forgot(employee);
        String code = latestCode(employee);

        clock.set(issuedAt.plus(Duration.ofMinutes(30)).minusSeconds(1));
        assertThat(reset(code).getResponse().getStatus()).isEqualTo(204);
    }
}
