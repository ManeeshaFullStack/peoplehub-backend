package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.peoplehub.common.logging.ActorId;
import com.peoplehub.employee.EmployeeLifecycleService;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestIdentities;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A login or refresh racing a deactivation can never leave a usable session once the deactivation
 * has committed (b2-6, B2-6/12; D26). Each interleaving is forced deterministically:
 *
 * <ul>
 *   <li>deactivation first: it is held open (uncommitted) while the login or refresh starts; that
 *       must wait for it, then fail;
 *   <li>login or refresh first: it is paused inside its token insert (after its status check) while
 *       the deactivation starts; the deactivation must wait for it, then revoke its new token too.
 * </ul>
 */
@IntegrationTest
class DeactivationRaceTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final InetAddress IP = InetAddress.getLoopbackAddress();

    /** How long a blocked call must stay blocked to count as waiting. */
    private static final long WAIT_MS = 700;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private LoginService loginService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private EmployeeLifecycleService lifecycleService;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private RefreshTokenStore store;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    /** When set, the next token insert signals {@code entered} and waits for {@code release}. */
    private volatile CountDownLatch insertEntered;

    private volatile CountDownLatch insertRelease;

    private TestIdentities.Organization org;
    private TestIdentities.Employee employee;
    private AuthenticatedPrincipal superAdmin;

    @BeforeEach
    void setUp() {
        doAnswer(
                        invocation -> {
                            CountDownLatch entered = insertEntered;
                            if (entered != null) {
                                insertEntered = null;
                                entered.countDown();
                                insertRelease.await(20, TimeUnit.SECONDS);
                            }
                            return invocation.callRealMethod();
                        })
                .when(store)
                .insert(any(), any(), any(), any(), any(), any(), any());
        org = TestIdentities.activeOrganization(jdbc);
        employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", passwordHasher.hash(PASSWORD));
        TestIdentities.Employee admin =
                TestIdentities.activeEmployee(
                        jdbc, org, "SUPER_ADMIN", passwordHasher.hash(PASSWORD));
        superAdmin =
                new AuthenticatedPrincipal(admin.id(), org.id(), "SUPER_ADMIN", UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        insertEntered = null;
        pool.shutdownNow();
    }

    private LoginRequest loginRequest() {
        return new LoginRequest(employee.organizationLoginKey(), employee.email(), PASSWORD);
    }

    private SessionTokens signIn() {
        ActorId.set(ActorId.ANONYMOUS);
        try {
            return new TransactionTemplate(transactionManager)
                    .execute(
                            status -> loginService.login(loginRequest(), IP, "Test").orElseThrow());
        } finally {
            ActorId.clear();
        }
    }

    private Callable<Optional<SessionTokens>> refresh(String rawRefreshToken) {
        return () -> {
            ActorId.set(ActorId.ANONYMOUS);
            try {
                return refreshTokenService.refresh(rawRefreshToken, IP);
            } finally {
                ActorId.clear();
            }
        };
    }

    private Callable<Optional<SessionTokens>> login() {
        return () -> {
            ActorId.set(ActorId.ANONYMOUS);
            try {
                return loginService.login(loginRequest(), IP, "Test");
            } finally {
                ActorId.clear();
            }
        };
    }

    /** Deactivates in its own transaction, committing when it returns. */
    private Callable<Void> deactivate() {
        return () -> {
            ActorId.set(superAdmin.employeeId().toString());
            try {
                lifecycleService.deactivate(superAdmin, employee.id(), null, IP);
                return null;
            } finally {
                ActorId.clear();
            }
        };
    }

    /** Deactivates, then keeps the transaction open (and its locks held) until released. */
    private Callable<Void> deactivateAndHold(CountDownLatch done, CountDownLatch release) {
        return () -> {
            ActorId.set(superAdmin.employeeId().toString());
            try {
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(
                                status -> {
                                    lifecycleService.deactivate(
                                            superAdmin, employee.id(), null, IP);
                                    done.countDown();
                                    try {
                                        release.await(20, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                return null;
            } finally {
                ActorId.clear();
            }
        };
    }

    private static void assertStillWaiting(Future<?> future) throws Exception {
        try {
            future.get(WAIT_MS, TimeUnit.MILLISECONDS);
            throw new AssertionError("expected the call to wait for the other transaction");
        } catch (TimeoutException expected) {
            // Still blocked on the row lock, as it must be.
        }
    }

    private int usableTokens() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE employee_id = ? AND NOT revoked",
                Integer.class,
                employee.id());
    }

    private int families() {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT family_id) FROM refresh_token WHERE employee_id = ?",
                Integer.class,
                employee.id());
    }

    private int reuseAudits() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ?"
                        + " AND action = 'REFRESH_TOKEN_REUSE_DETECTED'",
                Integer.class,
                org.id());
    }

    // ---- refresh ----

    @Test
    void aRefreshThatStartedFirstHasItsNewTokenRevokedByTheDeactivation() throws Exception {
        SessionTokens session = signIn();
        insertRelease = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        insertEntered = entered;

        Future<Optional<SessionTokens>> refreshing = pool.submit(refresh(session.refreshToken()));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Void> deactivating = pool.submit(deactivate());
        // The refresh holds the employee row; the deactivation must wait for it.
        assertStillWaiting(deactivating);
        insertRelease.countDown();

        Optional<SessionTokens> refreshed = refreshing.get(10, TimeUnit.SECONDS);
        deactivating.get(10, TimeUnit.SECONDS);

        assertThat(refreshed).isPresent();
        assertThat(usableTokens()).isZero();
        assertThat(refreshTokenService.refresh(refreshed.get().refreshToken(), IP)).isEmpty();
        assertThat(reuseAudits()).isZero();
    }

    @Test
    void aRefreshThatComesSecondWaitsForTheDeactivationAndFails() throws Exception {
        SessionTokens session = signIn();
        CountDownLatch deactivated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Void> deactivating = pool.submit(deactivateAndHold(deactivated, release));
        assertThat(deactivated.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Optional<SessionTokens>> refreshing = pool.submit(refresh(session.refreshToken()));
        assertStillWaiting(refreshing);
        release.countDown();
        deactivating.get(10, TimeUnit.SECONDS);

        assertThat(refreshing.get(10, TimeUnit.SECONDS)).isEmpty();
        assertThat(usableTokens()).isZero();
        assertThat(reuseAudits()).isZero();
    }

    // ---- login ----

    @Test
    void aLoginThatStartedFirstHasItsSessionEndedByTheDeactivation() throws Exception {
        insertRelease = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        insertEntered = entered;

        Future<Optional<SessionTokens>> loggingIn = pool.submit(login());
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Void> deactivating = pool.submit(deactivate());
        assertStillWaiting(deactivating);
        insertRelease.countDown();

        Optional<SessionTokens> session = loggingIn.get(10, TimeUnit.SECONDS);
        deactivating.get(10, TimeUnit.SECONDS);

        assertThat(session).isPresent();
        assertThat(usableTokens()).isZero();
        assertThat(refreshTokenService.refresh(session.get().refreshToken(), IP)).isEmpty();
    }

    @Test
    void aLoginThatComesSecondWaitsForTheDeactivationAndCreatesNoSession() throws Exception {
        CountDownLatch deactivated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Void> deactivating = pool.submit(deactivateAndHold(deactivated, release));
        assertThat(deactivated.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Optional<SessionTokens>> loggingIn = pool.submit(login());
        assertStillWaiting(loggingIn);
        release.countDown();
        deactivating.get(10, TimeUnit.SECONDS);

        assertThat(loggingIn.get(10, TimeUnit.SECONDS)).isEmpty();
        assertThat(families()).isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT success FROM login_attempt WHERE email_attempted = ?",
                                Boolean.class,
                                employee.email()))
                .containsExactly(false);
    }

    // ---- normal paths stay unchanged ----

    @Test
    void loginAndRefreshStillWorkAndParallelOnesDoNotBlockEachOtherForever() throws Exception {
        SessionTokens first = signIn();
        SessionTokens second = signIn();
        // A few failed sign-ins, so a successful login also has a row update to make.
        jdbc.update("UPDATE employee SET failed_login_count = 2 WHERE id = ?", employee.id());

        List<Callable<Optional<SessionTokens>>> calls = new ArrayList<>();
        calls.add(refresh(first.refreshToken()));
        calls.add(refresh(second.refreshToken()));
        List<Callable<Optional<SessionTokens>>> logins = List.of(login(), login());

        for (Future<Optional<SessionTokens>> result : pool.invokeAll(calls, 20, TimeUnit.SECONDS)) {
            assertThat(result.get()).isPresent();
        }
        for (Future<Optional<SessionTokens>> result :
                pool.invokeAll(logins, 20, TimeUnit.SECONDS)) {
            assertThat(result.get()).isPresent();
        }
        assertThat(families()).isEqualTo(4);
        assertThat(usableTokens()).isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT failed_login_count FROM employee WHERE id = ?",
                                Integer.class,
                                employee.id()))
                .isZero();
    }
}
