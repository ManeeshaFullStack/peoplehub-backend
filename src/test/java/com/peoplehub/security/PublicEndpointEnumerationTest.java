package com.peoplehub.security;

import static com.peoplehub.support.IndistinguishableResponses.assertIndistinguishable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.notification.email.EmailOutboxProcessorJob;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Organization and account enumeration through the public, pre-tenant entry points (b2-8 C5; Spec
 * D22, D27, 13.0, 15.1). For each entry point, every way to fail (an unknown organization, a known
 * organization with an unknown account, another organization's account, an account in the wrong
 * state, an unknown, expired, used or revoked token) must look exactly the same to the client:
 * status, whole body, every header and cookie. And a failure writes nothing into any organization.
 *
 * <p>Deterministic: it compares what a client can read and what the database holds, never
 * wall-clock time. Accepted residual (b2-8 C3, recorded again here): once V24 resolves a known
 * organization, the flow runs its ordinary organization-scoped lookups, one or two indexed queries
 * an unknown organization skips. The resulting difference is a fraction of a millisecond beside the
 * Argon2 verification every login attempt runs (one per attempt, known account or not), is not
 * reflected in anything the response carries, and is left to rate limiting (b13-1) rather than
 * hidden with artificial work.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PublicEndpointEnumerationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Enum-pass " + UUID.randomUUID();
    private static final String REFRESH_COOKIE = "__Secure-peoplehub_rt";
    private static final String CSRF_COOKIE = "__Secure-peoplehub_csrf";
    private static final String CSRF_HEADER = "X-CSRF-Token";

    private static final List<String> TENANT_TABLES =
            List.of(
                    "employee",
                    "employee_invitation",
                    "refresh_token",
                    "mfa_recovery_code",
                    "mfa_challenge",
                    "session_step_up",
                    "organization_verification_token",
                    "password_reset_token",
                    "audit_log",
                    "email_outbox",
                    "notification",
                    "notification_preference");

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private SecureTokens secureTokens;
    @Autowired private LockProvider lockProvider;

    private SimpleLock outboxJobLock;
    private final List<UUID> organizations = new ArrayList<>();

    @BeforeEach
    void holdTheOutboxJob() throws InterruptedException {
        // The scheduled outbox job would otherwise send this test's emails mid-test and change the
        // rows the side-effect checks compare.
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Optional<SimpleLock> lock = Optional.empty();
        while (lock.isEmpty()) {
            lock =
                    lockProvider.lock(
                            new LockConfiguration(
                                    Instant.now(),
                                    EmailOutboxProcessorJob.JOB_NAME,
                                    Duration.ofMinutes(5),
                                    Duration.ZERO));
            if (lock.isEmpty()) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("the outbox job never released its lock");
                }
                Thread.sleep(50);
            }
        }
        outboxJobLock = lock.get();
    }

    @AfterEach
    void releaseTheOutboxJob() {
        outboxJobLock.unlock();
    }

    // ---- login ----

    @Test
    void everyFailedLoginLooksTheSameWhateverTheOrganization() throws Exception {
        TestIdentities.Employee known = activeEmployee();
        TestIdentities.Employee elsewhere = activeEmployee();
        MvcResult baseline = login(known.organizationLoginKey(), known.email(), PASSWORD + "x");
        Map<String, String> before = fingerprints();

        Map<String, MvcResult> attempts = new LinkedHashMap<>();
        attempts.put(
                "unknown organization, real email",
                login("nobody-" + UUID.randomUUID(), known.email(), PASSWORD));
        attempts.put(
                "known organization, another organization's email",
                login(known.organizationLoginKey(), elsewhere.email(), PASSWORD));
        attempts.put(
                "known organization, unknown email",
                login(
                        known.organizationLoginKey(),
                        "nobody-" + UUID.randomUUID() + "@x.io",
                        PASSWORD));
        attempts.put(
                "another organization's id instead of a key",
                login(elsewhere.organizationId().toString(), elsewhere.email(), PASSWORD));
        attempts.put("no letter or digit", login("---", known.email(), PASSWORD));

        for (Map.Entry<String, MvcResult> attempt : attempts.entrySet()) {
            assertIndistinguishable(baseline, attempt.getValue(), attempt.getKey());
        }
        assertThat(baseline.getResponse().getStatus()).isEqualTo(401);
        assertThat(baseline.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(fingerprints()).as("no organization was written to").isEqualTo(before);
    }

    // ---- forgot password and resend verification ----

    @Test
    void forgotPasswordAnswersTheSameAndWritesOnlyForARealActiveAccount() throws Exception {
        TestIdentities.Employee active = activeEmployee();
        TestIdentities.Employee elsewhere = activeEmployee();
        TestIdentities.Employee deactivated = activeEmployee(active);
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", deactivated.id());
        Map<String, String> before = fingerprints();

        Map<String, MvcResult> attempts = new LinkedHashMap<>();
        attempts.put("unknown organization", forgot("nobody-" + UUID.randomUUID(), active.email()));
        attempts.put(
                "known organization, unknown email",
                forgot(active.organizationLoginKey(), "nobody-" + UUID.randomUUID() + "@x.io"));
        attempts.put(
                "known organization, another organization's email",
                forgot(active.organizationLoginKey(), elsewhere.email()));
        attempts.put(
                "deactivated account", forgot(active.organizationLoginKey(), deactivated.email()));
        assertThat(fingerprints()).as("no failure writes anything").isEqualTo(before);

        MvcResult real = forgot(active.organizationLoginKey(), active.email());
        assertThat(real.getResponse().getStatus()).isEqualTo(202);
        for (Map.Entry<String, MvcResult> attempt : attempts.entrySet()) {
            assertIndistinguishable(real, attempt.getValue(), attempt.getKey());
        }
        assertThat(fingerprints()).as("the real account got its reset email").isNotEqualTo(before);
    }

    @Test
    void resendVerificationAnswersTheSameAndWritesOnlyForARealPendingFounder() throws Exception {
        Founder pending = pendingFounder();
        TestIdentities.Employee activeFounder = activeEmployee();
        Map<String, String> before = fingerprints();

        Map<String, MvcResult> attempts = new LinkedHashMap<>();
        attempts.put(
                "unknown organization", resend("nobody-" + UUID.randomUUID(), pending.email()));
        attempts.put(
                "pending organization, wrong email",
                resend(pending.loginKey(), "nobody-" + UUID.randomUUID() + "@x.io"));
        attempts.put(
                "already active organization",
                resend(activeFounder.organizationLoginKey(), activeFounder.email()));
        assertThat(fingerprints()).as("no failure writes anything").isEqualTo(before);

        MvcResult real = resend(pending.loginKey(), pending.email());
        assertThat(real.getResponse().getStatus()).isEqualTo(200);
        for (Map.Entry<String, MvcResult> attempt : attempts.entrySet()) {
            assertIndistinguishable(real, attempt.getValue(), attempt.getKey());
        }
    }

    // ---- tokens: every unusable token is the same unusable token ----

    @Test
    void everyUnusableVerificationTokenLooksTheSame() throws Exception {
        UUID org = pendingFounder().organizationId();
        String expired =
                token(
                        "INSERT INTO organization_verification_token (organization_id, token_hash, expires_at) VALUES (?, ?, now() - interval '1 hour')",
                        org,
                        HASH);
        String used =
                token(
                        "INSERT INTO organization_verification_token (organization_id, token_hash, expires_at, consumed_at) VALUES (?, ?, now() + interval '1 day', now())",
                        org,
                        HASH);
        Map<String, String> before = fingerprints();

        MvcResult unknown = verify(secureTokens.generateRaw());
        assertThat(unknown.getResponse().getStatus()).isEqualTo(200);
        assertIndistinguishable(unknown, verify(expired), "expired");
        assertIndistinguishable(unknown, verify(used), "used");
        assertThat(fingerprints()).isEqualTo(before);
    }

    @Test
    void everyUnusableResetCodeLooksTheSame() throws Exception {
        TestIdentities.Employee employee = activeEmployee();
        String expired =
                token(
                        "INSERT INTO password_reset_token (organization_id, employee_id, token_hash, expires_at) VALUES (?, ?, ?, now() - interval '1 minute')",
                        employee.organizationId(),
                        employee.id(),
                        HASH);
        String used =
                token(
                        "INSERT INTO password_reset_token (organization_id, employee_id, token_hash, expires_at, consumed_at) VALUES (?, ?, ?, now() + interval '30 minutes', now())",
                        employee.organizationId(),
                        employee.id(),
                        HASH);
        Map<String, String> before = fingerprints();

        MvcResult unknown = reset(secureTokens.generateRaw());
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertIndistinguishable(unknown, reset(expired), "expired");
        assertIndistinguishable(unknown, reset(used), "used");
        assertThat(fingerprints()).isEqualTo(before);
    }

    @Test
    void everyUnusableInvitationLooksTheSameToPreviewAndAccept() throws Exception {
        TestIdentities.Employee inviter = activeEmployee();
        Map<String, String> invitations = new LinkedHashMap<>();
        invitations.put("expired", invitation(inviter, "now() - interval '1 minute'", null, null));
        invitations.put("used", invitation(inviter, "now() + interval '7 days'", "now()", null));
        invitations.put("revoked", invitation(inviter, "now() + interval '7 days'", null, "now()"));
        Map<String, String> before = fingerprints();

        String unknown = secureTokens.generateRaw();
        MvcResult unknownPreview =
                mvc.perform(get("/api/v1/public/invitations/" + unknown + "/preview")).andReturn();
        MvcResult unknownAccept = accept(unknown);
        assertThat(unknownPreview.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknownAccept.getResponse().getStatus()).isEqualTo(404);
        for (Map.Entry<String, String> invitation : invitations.entrySet()) {
            assertIndistinguishable(
                    unknownPreview,
                    mvc.perform(
                                    get(
                                            "/api/v1/public/invitations/"
                                                    + invitation.getValue()
                                                    + "/preview"))
                            .andReturn(),
                    invitation.getKey() + " preview");
            assertIndistinguishable(
                    unknownAccept, accept(invitation.getValue()), invitation.getKey() + " accept");
        }
        assertThat(fingerprints()).isEqualTo(before);
    }

    @Test
    void everyUnusableMfaChallengeLooksTheSame() throws Exception {
        TestIdentities.Employee employee = activeEmployee();
        String expired =
                token(
                        "INSERT INTO mfa_challenge (organization_id, employee_id, token_hash, purpose, expires_at) VALUES (?, ?, ?, 'CHALLENGE', now() - interval '1 minute')",
                        employee.organizationId(),
                        employee.id(),
                        HASH);
        String used =
                token(
                        "INSERT INTO mfa_challenge (organization_id, employee_id, token_hash, purpose, expires_at, consumed_at) VALUES (?, ?, ?, 'CHALLENGE', now() + interval '5 minutes', now())",
                        employee.organizationId(),
                        employee.id(),
                        HASH);
        String wrongPurpose =
                token(
                        "INSERT INTO mfa_challenge (organization_id, employee_id, token_hash, purpose, expires_at) VALUES (?, ?, ?, 'ENROLL', now() + interval '5 minutes')",
                        employee.organizationId(),
                        employee.id(),
                        HASH);
        Map<String, String> before = fingerprints();

        MvcResult unknown = challenge(secureTokens.generateRaw());
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertIndistinguishable(unknown, challenge(expired), "expired");
        assertIndistinguishable(unknown, challenge(used), "used");
        assertIndistinguishable(unknown, challenge(wrongPurpose), "an enrollment challenge");
        assertThat(fingerprints()).isEqualTo(before);
    }

    @Test
    void everyUnusableRefreshTokenLooksTheSame() throws Exception {
        TestIdentities.Employee employee = activeEmployee();
        String expired =
                token(
                        "INSERT INTO refresh_token (organization_id, employee_id, token_hash, family_id, expires_at, absolute_expires_at) VALUES (?, ?, ?, gen_random_uuid(), now() - interval '1 minute', now() + interval '1 day')",
                        employee.organizationId(),
                        employee.id(),
                        HASH);
        TestIdentities.Employee deactivated = activeEmployee();
        String ofDeactivated =
                token(
                        "INSERT INTO refresh_token (organization_id, employee_id, token_hash, family_id, expires_at, absolute_expires_at) VALUES (?, ?, ?, gen_random_uuid(), now() + interval '1 day', now() + interval '2 days')",
                        deactivated.organizationId(),
                        deactivated.id(),
                        HASH);
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", deactivated.id());
        Map<String, String> before = fingerprints();

        MvcResult unknown = refresh(secureTokens.generateRaw(), null);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertIndistinguishable(unknown, refresh(expired, null), "expired");
        assertIndistinguishable(unknown, refresh(ofDeactivated, null), "a deactivated account's");
        assertThat(fingerprints()).isEqualTo(before);
    }

    // ---- a token combined with another organization's context ----

    @Test
    void aRefreshAlwaysContinuesTheTokensOwnSessionWhateverBearerComesWithIt() throws Exception {
        TestIdentities.Employee inA = activeEmployee();
        TestIdentities.Employee inB = activeEmployee();
        MvcResult loginA = login(inA.organizationLoginKey(), inA.email(), PASSWORD);
        MvcResult loginB = login(inB.organizationLoginKey(), inB.email(), PASSWORD);
        String refreshA = loginA.getResponse().getCookie(REFRESH_COOKIE).getValue();
        String bearerB = "Bearer " + body(loginB).get("accessToken");

        MvcResult refreshed = refresh(refreshA, bearerB);

        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> claims = claims((String) body(refreshed).get("accessToken"));
        assertThat(claims.get("org"))
                .as("A's session stays A's")
                .isEqualTo(inA.organizationId().toString());
        assertThat(claims.get("sub")).isEqualTo(inA.id().toString());
    }

    // ---- helpers ----

    private record Founder(UUID organizationId, String loginKey, String email) {}

    private TestIdentities.Employee activeEmployee() {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        organizations.add(org.id());
        return TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", passwordHasher.hash(PASSWORD));
    }

    /** Another employee in the same organization as {@code colleague}. */
    private TestIdentities.Employee activeEmployee(TestIdentities.Employee colleague) {
        return TestIdentities.activeEmployee(
                jdbc,
                new TestIdentities.Organization(
                        colleague.organizationId(), colleague.organizationLoginKey()),
                "EMPLOYEE",
                passwordHasher.hash(PASSWORD));
    }

    private Founder pendingFounder() {
        String key = "pending-" + UUID.randomUUID();
        UUID org =
                jdbc.queryForObject(
                        "INSERT INTO organization (name, login_key_normalized, timezone)"
                                + " VALUES ('Pending', ?, 'UTC') RETURNING id",
                        UUID.class,
                        key);
        organizations.add(org);
        String email = "founder-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, status, role, join_date) VALUES (?, ?, 'Pat"
                        + " Pending', ?, ?, 'PENDING_VERIFICATION', 'SUPER_ADMIN', DATE"
                        + " '2026-01-05')",
                org,
                "E-" + UUID.randomUUID(),
                email,
                email);
        return new Founder(org, key, email);
    }

    /** Stands for the new token's hash among {@link #token}'s parameters. */
    private static final Object HASH = new Object();

    /** Inserts a token row, {@link #HASH} being replaced by its hash; returns the raw token. */
    private String token(String insert, Object... params) {
        String raw = secureTokens.generateRaw();
        Object[] values = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            values[i] = params[i] == HASH ? secureTokens.hash(raw) : params[i];
        }
        jdbc.update(insert, values);
        return raw;
    }

    private String invitation(
            TestIdentities.Employee inviter,
            String expiresAt,
            String consumedAt,
            String revokedAt) {
        String email = "invitee-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, status, role, join_date) VALUES (?, ?, 'Ivy"
                        + " Invitee', ?, ?, 'INVITED', 'EMPLOYEE', DATE '2026-01-05')",
                inviter.organizationId(),
                "E-" + UUID.randomUUID(),
                email,
                email);
        String raw = secureTokens.generateRaw();
        jdbc.update(
                "INSERT INTO employee_invitation (organization_id, email_normalized,"
                        + " intended_role, token_hash, inviter_employee_id, expires_at,"
                        + " consumed_at, revoked_at) VALUES (?, ?, 'EMPLOYEE', ?, ?, "
                        + expiresAt
                        + ", "
                        + (consumedAt == null ? "NULL" : consumedAt)
                        + ", "
                        + (revokedAt == null ? "NULL" : revokedAt)
                        + ")",
                inviter.organizationId(),
                email,
                secureTokens.hash(raw),
                inviter.id());
        return raw;
    }

    private MvcResult login(String organization, String email, String password) throws Exception {
        return send(
                post("/api/v1/auth/login"),
                Map.of("organization", organization, "email", email, "password", password));
    }

    private MvcResult forgot(String organization, String email) throws Exception {
        return send(
                post("/api/v1/auth/forgot-password"),
                Map.of("organization", organization, "email", email));
    }

    private MvcResult resend(String loginKey, String email) throws Exception {
        return send(
                post("/api/v1/public/organizations/resend-verification"),
                Map.of("organizationLoginKey", loginKey, "companyEmail", email));
    }

    private MvcResult verify(String token) throws Exception {
        return send(post("/api/v1/public/organizations/verify-email"), Map.of("token", token));
    }

    private MvcResult reset(String token) throws Exception {
        String password = "A brand new passphrase " + UUID.randomUUID();
        return send(
                post("/api/v1/auth/reset-password"),
                Map.of("token", token, "password", password, "confirmPassword", password));
    }

    private MvcResult accept(String token) throws Exception {
        return send(
                post("/api/v1/public/invitations/" + token + "/accept"),
                Map.of("password", PASSWORD, "confirmPassword", PASSWORD));
    }

    private MvcResult challenge(String token) throws Exception {
        return send(
                post("/api/v1/auth/mfa/challenge"),
                Map.of("challengeToken", token, "code", "123456"));
    }

    private MvcResult refresh(String refreshToken, String bearer) throws Exception {
        String csrf = "csrf-" + "fixed";
        MockHttpServletRequestBuilder request =
                post("/api/v1/auth/refresh")
                        .cookie(
                                new Cookie(REFRESH_COOKIE, refreshToken),
                                new Cookie(CSRF_COOKIE, csrf))
                        .header(CSRF_HEADER, csrf);
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mvc.perform(
                        request.contentType("application/json")
                                .content(JSON.writeValueAsString(body)))
                .andReturn();
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private static Map<String, Object> claims(String jwt) throws Exception {
        String payload = jwt.split("\\.")[1];
        return JSON.readValue(
                new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8),
                new TypeReference<>() {});
    }

    /** A digest of every row every organization of this test owns. */
    private Map<String, String> fingerprints() {
        Map<String, String> fingerprints = new TreeMap<>();
        for (UUID org : organizations) {
            StringBuilder digest =
                    new StringBuilder(
                            jdbc.queryForObject(
                                    "SELECT md5(t::text) FROM organization t WHERE id = ?",
                                    String.class,
                                    org));
            for (String table : TENANT_TABLES) {
                digest.append(';')
                        .append(
                                jdbc.queryForObject(
                                        "SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY"
                                                + " t::text)), '') FROM "
                                                + table
                                                + " t WHERE organization_id = ?",
                                        String.class,
                                        org));
            }
            fingerprints.put(org.toString(), digest.toString());
        }
        return fingerprints;
    }
}
