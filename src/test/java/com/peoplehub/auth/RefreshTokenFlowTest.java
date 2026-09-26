package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.common.logging.ActorId;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import jakarta.servlet.http.Cookie;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code POST /api/v1/auth/refresh} (b2-3, B2-3/3, B2-3/5-B2-3/8, B2-3/10, B2-3/11): rotation,
 * reuse detection, the sliding and absolute limits and the access-token lifetime on a controllable
 * clock, and the CSRF and Origin checks. No app origin is configured here, so any {@code Origin}
 * header is refused and requests without one pass.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
class RefreshTokenFlowTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String REFRESH = "/api/v1/auth/refresh";

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private MutableClock clock;
    @Autowired private RefreshTokenService refreshTokenService;

    private AuthTestClient client;
    private TestIdentities.Employee employee;

    @BeforeEach
    void anActiveEmployee() {
        // Microseconds: what a timestamptz column stores, so stored expiries compare exactly.
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        client = new AuthTestClient(mvc);
        employee =
                TestIdentities.activeEmployee(
                        jdbc,
                        TestIdentities.activeOrganization(jdbc),
                        "ADMIN",
                        passwordHasher.hash(PASSWORD));
    }

    private List<Map<String, Object>> tokenRows() {
        return jdbc.queryForList(
                "SELECT id, family_id, revoked, revoke_reason, replaced_by_id, expires_at,"
                        + " absolute_expires_at FROM refresh_token WHERE employee_id = ?"
                        + " ORDER BY created_at, expires_at",
                employee.id());
    }

    private void assertSessionEnded(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(AuthTestClient.body(result))
                .containsEntry("type", "urn:peoplehub:problem:unauthorized")
                .containsEntry("detail", "Your session has ended. Please sign in again.");
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .hasSize(2)
                .allSatisfy(cookie -> assertThat(cookie).contains("Max-Age=0"));
    }

    // ---- rotation ----

    @Test
    void aRefreshRotatesTheTokenInsideTheSameFamily() throws Exception {
        AuthTestClient.Session first = client.login(employee, PASSWORD);
        clock.advance(Duration.ofMinutes(10));

        MvcResult result = client.refresh(first);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        AuthTestClient.Session second = AuthTestClient.session(result);
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.csrfToken()).isNotEqualTo(first.csrfToken());
        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(client.meStatus(second)).isEqualTo(200);

        List<Map<String, Object>> rows = tokenRows();
        assertThat(rows).hasSize(2);
        Map<String, Object> old = rows.get(0);
        Map<String, Object> current = rows.get(1);
        assertThat(old)
                .containsEntry("revoked", true)
                .containsEntry("revoke_reason", "ROTATED")
                .containsEntry("replaced_by_id", current.get("id"));
        assertThat(current)
                .containsEntry("revoked", false)
                .containsEntry("family_id", old.get("family_id"))
                .containsEntry("absolute_expires_at", old.get("absolute_expires_at"));
        // The sliding window restarted at the refresh.
        assertThat(((Timestamp) current.get("expires_at")).toInstant())
                .isEqualTo(clock.instant().plus(Duration.ofDays(30)));
    }

    @Test
    void theAccessTokenFromBeforeARefreshStaysValidUntilItExpires() throws Exception {
        AuthTestClient.Session first = client.login(employee, PASSWORD);
        client.refresh(first);

        assertThat(client.meStatus(first)).isEqualTo(200);
    }

    // ---- reuse detection (B2-3/8) ----

    @Test
    void reusingARotatedTokenEndsTheWholeSessionAndIsAudited() throws Exception {
        AuthTestClient.Session first = client.login(employee, PASSWORD);
        AuthTestClient.Session second = AuthTestClient.session(client.refresh(first));

        // The old token is presented again: someone copied it.
        assertSessionEnded(client.refresh(first));

        assertThat(tokenRows()).allSatisfy(row -> assertThat(row).containsEntry("revoked", true));
        assertThat(tokenRows().get(1)).containsEntry("revoke_reason", "REUSE_DETECTED");
        // The legitimate holder of the newest token is signed out too, immediately.
        assertSessionEnded(client.refresh(second));
        assertThat(client.meStatus(second)).isEqualTo(401);

        List<Map<String, Object>> audit =
                jdbc.queryForList(
                        "SELECT actor_id, target_id,"
                                + " details -> 'attributes' ->> 'sessionId' AS session_id"
                                + " FROM audit_log WHERE organization_id = ?"
                                + " AND action = 'REFRESH_TOKEN_REUSE_DETECTED'",
                        employee.organizationId());
        assertThat(audit).isNotEmpty();
        assertThat(audit.get(0))
                .containsEntry("actor_id", "anonymous")
                .containsEntry("target_id", employee.id().toString())
                .containsEntry("session_id", tokenRows().get(0).get("family_id").toString());
    }

    @Test
    void twoParallelRefreshesOfTheSameTokenEndTheSession() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<SessionTokens>> refresh =
                () -> {
                    ActorId.set(ActorId.ANONYMOUS);
                    try {
                        start.await();
                        return refreshTokenService.refresh(
                                session.refreshToken(), InetAddress.getLoopbackAddress());
                    } finally {
                        ActorId.clear();
                    }
                };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<SessionTokens>>> results = new ArrayList<>();
            results.add(pool.submit(refresh));
            results.add(pool.submit(refresh));
            start.countDown();
            int granted = 0;
            for (Future<Optional<SessionTokens>> result : results) {
                granted += result.get().isPresent() ? 1 : 0;
            }

            // The row lock serializes them: one rotates, the other then sees a revoked token.
            assertThat(granted).isEqualTo(1);
            assertThat(tokenRows())
                    .hasSize(2)
                    .allSatisfy(row -> assertThat(row).containsEntry("revoked", true));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- lifetimes (B2-3/3, B2-3/5) ----

    @Test
    void theAccessTokenLastsFifteenMinutesPlusThirtySecondsOfSkew() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);

        clock.advance(Duration.ofMinutes(15).plusSeconds(29));
        assertThat(client.meStatus(session)).isEqualTo(200);

        clock.advance(Duration.ofSeconds(2));
        assertThat(client.meStatus(session)).isEqualTo(401);
    }

    @Test
    void anUnusedSessionEndsAfterThirtyDays() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);

        clock.advance(Duration.ofDays(30).plusSeconds(1));

        assertSessionEnded(client.refresh(session));
        // Expiry is not reuse: nothing was revoked.
        assertThat(tokenRows())
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("revoked", false));
    }

    @Test
    void refreshingNeverExtendsASessionPastNinetyDays() throws Exception {
        Instant loggedInAt = clock.instant();
        AuthTestClient.Session session = client.login(employee, PASSWORD);

        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofDays(29));
            MvcResult result = client.refresh(session);
            assertThat(result.getResponse().getStatus()).as("refresh " + i).isEqualTo(200);
            session = AuthTestClient.session(result);
        }

        // At day 87 the next sliding expiry (day 117) is capped at day 90.
        Map<String, Object> current = tokenRows().get(3);
        assertThat(((Timestamp) current.get("expires_at")).toInstant())
                .isEqualTo(loggedInAt.plus(Duration.ofDays(90)));
        assertThat(((Timestamp) current.get("absolute_expires_at")).toInstant())
                .isEqualTo(loggedInAt.plus(Duration.ofDays(90)));

        clock.advance(Duration.ofDays(3).plusSeconds(1));
        assertSessionEnded(client.refresh(session));
    }

    // ---- who may refresh ----

    @Test
    void aDeactivatedEmployeeCannotRefresh() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", employee.id());

        assertSessionEnded(client.refresh(session));
        assertThat(tokenRows()).hasSize(1);
    }

    @Test
    void aSuspendedOrganizationCannotRefresh() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);
        jdbc.update(
                "UPDATE organization SET status = 'SUSPENDED' WHERE id = ?",
                employee.organizationId());

        assertSessionEnded(client.refresh(session));
    }

    @Test
    void anUnknownOrMissingRefreshTokenEndsTheSession() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);

        assertSessionEnded(
                client.refresh(
                        new AuthTestClient.Session(
                                session.accessToken(), "not-a-real-token", session.csrfToken())));
        assertSessionEnded(mvc.perform(post(REFRESH)).andReturn());
    }

    // ---- CSRF and Origin (B2-3/11) ----

    @Test
    void aRefreshWithoutAMatchingCsrfTokenIsForbiddenAndChangesNothing() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);
        Cookie refreshCookie = new Cookie(AuthCookies.REFRESH_COOKIE, session.refreshToken());
        Cookie csrfCookie = new Cookie(AuthCookies.CSRF_COOKIE, session.csrfToken());

        MvcResult[] attempts = {
            // no header
            mvc.perform(post(REFRESH).cookie(refreshCookie, csrfCookie)).andReturn(),
            // header but no cookie
            mvc.perform(
                            post(REFRESH)
                                    .cookie(refreshCookie)
                                    .header(CsrfOriginGuard.CSRF_HEADER, session.csrfToken()))
                    .andReturn(),
            // mismatch
            mvc.perform(
                            post(REFRESH)
                                    .cookie(refreshCookie, csrfCookie)
                                    .header(CsrfOriginGuard.CSRF_HEADER, "guessed"))
                    .andReturn()
        };

        for (MvcResult attempt : attempts) {
            assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
            assertThat(AuthTestClient.body(attempt))
                    .containsEntry("type", "urn:peoplehub:problem:forbidden");
        }
        assertThat(tokenRows())
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("revoked", false));
        assertThat(client.refresh(session).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aRefreshFromAForeignOriginIsForbidden() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);

        MvcResult result =
                mvc.perform(
                                session.withCookies(post(REFRESH))
                                        .header("Origin", "https://evil.example"))
                        .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(tokenRows())
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("revoked", false));
    }

    @Test
    void theRefreshResponseNeverContainsTheRefreshToken() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);

        MvcResult result = client.refresh(session);

        String newRaw = result.getResponse().getCookie(AuthCookies.REFRESH_COOKIE).getValue();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(newRaw)
                .doesNotContain(session.refreshToken());
        assertThat(AuthTestClient.body(result))
                .containsOnlyKeys("accessToken", "tokenType", "expiresIn", "csrfToken");
    }

    @Test
    void aStaleBearerTokenDoesNotBlockARefresh() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);

        MvcResult result =
                mvc.perform(
                                session.withCookies(post(REFRESH))
                                        .header("Authorization", "Bearer " + UUID.randomUUID()))
                        .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }
}
