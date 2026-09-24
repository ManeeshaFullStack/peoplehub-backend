package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.TestIdentities;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-account login lockout end to end (b2-5, B2-5/P3, P4, R2, R3): the 5th consecutive failure
 * locks the account for 1 minute, doubling to a 30-minute cap; a locked account answers exactly
 * like a wrong password, still runs one Argon2 check, and is neither counted nor extended; a
 * success clears it.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
class LoginLockoutTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String WRONG = "not-the-password-" + UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private LoginService loginService;
    @MockitoSpyBean private PasswordHasher passwordHasher;

    private TestIdentities.Employee employee() {
        return TestIdentities.activeEmployee(
                jdbc,
                TestIdentities.activeOrganization(jdbc),
                "EMPLOYEE",
                passwordHasher.hash(PASSWORD));
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

    private void fail(TestIdentities.Employee employee, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(login(employee, WRONG).getResponse().getStatus()).isEqualTo(401);
        }
    }

    private int failedCount(TestIdentities.Employee employee) {
        return jdbc.queryForObject(
                "SELECT failed_login_count FROM employee WHERE id = ?",
                Integer.class,
                employee.id());
    }

    private Instant lockedUntil(TestIdentities.Employee employee) {
        Timestamp until =
                jdbc.queryForObject(
                        "SELECT locked_until FROM employee WHERE id = ?",
                        Timestamp.class,
                        employee.id());
        return until == null ? null : until.toInstant();
    }

    private List<Map<String, Object>> lockAudits(TestIdentities.Employee employee) {
        return jdbc.queryForList(
                "SELECT actor_id, target_type, target_id, host(ip) AS ip,"
                        + " details -> 'attributes' ->> 'failedLoginCount' AS failed_login_count,"
                        + " details -> 'attributes' ->> 'lockSeconds' AS lock_seconds"
                        + " FROM audit_log WHERE organization_id = ? AND action = 'ACCOUNT_LOCKED'"
                        + " ORDER BY id",
                employee.organizationId());
    }

    private int auditRows(UUID organizationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ?",
                Integer.class,
                organizationId);
    }

    private static Map<String, Object> comparableBody(MvcResult result) throws Exception {
        Map<String, Object> body =
                JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
        body.remove("instance");
        body.remove("correlationId");
        return body;
    }

    @Test
    void fourFailuresAreCountedWithoutLocking() throws Exception {
        TestIdentities.Employee employee = employee();

        fail(employee, 4);

        assertThat(failedCount(employee)).isEqualTo(4);
        assertThat(lockedUntil(employee)).isNull();
        assertThat(lockAudits(employee)).isEmpty();
        assertThat(login(employee, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(failedCount(employee)).isZero();
    }

    @Test
    void theFifthFailureLocksForOneMinuteAndIsAudited() throws Exception {
        TestIdentities.Employee employee = employee();
        Instant now = clock.instant();

        fail(employee, 5);

        assertThat(failedCount(employee)).isEqualTo(5);
        assertThat(lockedUntil(employee)).isEqualTo(now.plus(Duration.ofMinutes(1)));
        List<Map<String, Object>> audits = lockAudits(employee);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .containsEntry("actor_id", "anonymous")
                .containsEntry("target_type", "EMPLOYEE")
                .containsEntry("target_id", employee.id().toString())
                .containsEntry("ip", "127.0.0.1")
                .containsEntry("failed_login_count", "5")
                .containsEntry("lock_seconds", "60");
        // Only the lock itself is audited, never the failed attempts.
        assertThat(auditRows(employee.organizationId())).isEqualTo(1);
    }

    @Test
    void aLockedAccountAnswersLikeAWrongPasswordAndIsNotExtended() throws Exception {
        TestIdentities.Employee employee = employee();
        fail(employee, 4);
        Map<String, Object> wrongPassword = comparableBody(login(employee, WRONG));
        Instant lockedUntil = lockedUntil(employee);
        assertThat(lockedUntil).isNotNull();

        for (String password : List.of(PASSWORD, WRONG)) {
            clearInvocations(passwordHasher);

            MvcResult result = login(employee, password);

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
            assertThat(comparableBody(result)).isEqualTo(wrongPassword);
            verify(passwordHasher, times(1)).matches(anyString(), anyString());
        }

        assertThat(failedCount(employee)).isEqualTo(5);
        assertThat(lockedUntil(employee)).isEqualTo(lockedUntil);
        assertThat(lockAudits(employee)).hasSize(1);
        // Attempts while locked are still recorded, as failures.
        assertThat(
                        jdbc.queryForList(
                                "SELECT success FROM login_attempt"
                                        + " WHERE organization_login_key_attempted = ?",
                                Boolean.class,
                                employee.organizationLoginKey()))
                .hasSize(7)
                .containsOnly(false);
    }

    @Test
    void afterTheLockExpiresTheRightPasswordWorksAndClearsTheState() throws Exception {
        TestIdentities.Employee employee = employee();
        fail(employee, 5);

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(login(employee, PASSWORD).getResponse().getStatus()).isEqualTo(200);

        assertThat(failedCount(employee)).isZero();
        assertThat(lockedUntil(employee)).isNull();
        // A fresh run of failures starts from zero again.
        fail(employee, 4);
        assertThat(lockedUntil(employee)).isNull();
    }

    @Test
    void eachFailureAfterALockExpiresDoublesTheLockUpToThirtyMinutes() throws Exception {
        TestIdentities.Employee employee = employee();
        fail(employee, 5);

        List<Long> lockMinutes = new ArrayList<>();
        for (int round = 0; round < 6; round++) {
            clock.set(lockedUntil(employee).plusSeconds(1));
            Instant now = clock.instant();
            fail(employee, 1);
            lockMinutes.add(Duration.between(now, lockedUntil(employee)).toMinutes());
        }

        assertThat(lockMinutes).containsExactly(2L, 4L, 8L, 16L, 30L, 30L);
        assertThat(failedCount(employee)).isEqualTo(11);
        assertThat(lockAudits(employee))
                .extracting(row -> row.get("lock_seconds"))
                .containsExactly("60", "120", "240", "480", "960", "1800", "1800");
    }

    @Test
    void theSameEmailInAnotherOrganizationIsNotAffected() throws Exception {
        TestIdentities.Employee locked = employee();
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);
        UUID otherId =
                jdbc.queryForObject(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, status, role, password_hash)"
                                + " VALUES (?, ?, 'Jane B', ?, ?, 'ACTIVE', 'EMPLOYEE', ?)"
                                + " RETURNING id",
                        UUID.class,
                        other.id(),
                        "E-" + UUID.randomUUID(),
                        locked.email(),
                        locked.email(),
                        passwordHasher.hash(PASSWORD));
        TestIdentities.Employee sameEmail =
                new TestIdentities.Employee(
                        otherId, other.id(), other.loginKey(), locked.email(), "EMPLOYEE");

        fail(locked, 5);

        assertThat(lockedUntil(locked)).isNotNull();
        assertThat(failedCount(sameEmail)).isZero();
        assertThat(lockedUntil(sameEmail)).isNull();
        assertThat(auditRows(other.id())).isZero();
        assertThat(login(sameEmail, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anUnknownAccountChangesNothing() throws Exception {
        TestIdentities.Employee employee = employee();
        TestIdentities.Employee unknownEmail =
                new TestIdentities.Employee(
                        UUID.randomUUID(),
                        employee.organizationId(),
                        employee.organizationLoginKey(),
                        "nobody-" + UUID.randomUUID() + "@example.com",
                        "EMPLOYEE");

        fail(unknownEmail, 6);

        assertThat(failedCount(employee)).isZero();
        assertThat(lockedUntil(employee)).isNull();
        assertThat(auditRows(employee.organizationId())).isZero();
    }

    @Test
    void aFailureForAnAccountThatCannotSignInIsStillCounted() throws Exception {
        TestIdentities.Employee employee = employee();
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", employee.id());

        fail(employee, 1);

        // Counting does not depend on status, so its behaviour reveals nothing about the account.
        assertThat(failedCount(employee)).isEqualTo(1);
    }

    @Test
    void parallelFailuresAreAllCounted() throws Exception {
        TestIdentities.Employee employee = employee();
        LoginRequest wrong =
                new LoginRequest(employee.organizationLoginKey(), employee.email(), WRONG);
        InetAddress ip = InetAddress.getLoopbackAddress();
        int parallel = 4;

        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < parallel; i++) {
                attempts.add(() -> loginService.login(wrong, ip, DeviceLabels.UNKNOWN).isPresent());
            }
            for (Future<Boolean> attempt : pool.invokeAll(attempts)) {
                assertThat(attempt.get()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failedCount(employee)).isEqualTo(parallel);
    }
}
