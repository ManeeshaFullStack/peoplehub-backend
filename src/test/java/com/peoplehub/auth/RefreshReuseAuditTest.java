package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.SignedJWT;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestIdentities;
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
 * Only a token that a refresh already replaced ({@code ROTATED}) or one presented after logout
 * ({@code LOGOUT}) counts as reuse and is audited as {@code REFRESH_TOKEN_REUSE_DETECTED}. A token
 * whose session was ended by a lifecycle action (a revoked session, a password reset or change,
 * deactivation) is refused with the same 401 but raises no false theft alarm, and its revoke reason
 * is left as it was.
 */
@IntegrationTest
@AutoConfigureMockMvc
class RefreshReuseAuditTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String NEW_PASSWORD = "Vq7#nR3tLm9@pZx2Kd";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;

    private AuthTestClient client;
    private TestIdentities.Employee employee;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(mvc);
        employee =
                TestIdentities.activeEmployee(
                        jdbc,
                        TestIdentities.activeOrganization(jdbc),
                        "EMPLOYEE",
                        passwordHasher.hash(PASSWORD));
    }

    private int reuseAudits() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ?"
                        + " AND action = 'REFRESH_TOKEN_REUSE_DETECTED'",
                Integer.class,
                employee.organizationId());
    }

    private static UUID sessionId(AuthTestClient.Session session) throws Exception {
        return UUID.fromString(
                SignedJWT.parse(session.accessToken()).getJWTClaimsSet().getStringClaim("sid"));
    }

    private String reasons(AuthTestClient.Session session) throws Exception {
        return jdbc.queryForObject(
                "SELECT string_agg(DISTINCT coalesce(revoke_reason, 'ACTIVE'), ',')"
                        + " FROM refresh_token WHERE family_id = ?",
                String.class,
                sessionId(session));
    }

    /** The refresh is refused like any ended session: 401, and the cookies are cleared. */
    private void assertRefused(AuthTestClient.Session session) throws Exception {
        MvcResult result = client.refresh(session);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(AuthTestClient.body(result))
                .containsEntry("detail", AuthController.SESSION_ENDED);
    }

    @Test
    void reusingARotatedTokenIsStillDetectedAndAuditedOnce() throws Exception {
        AuthTestClient.Session first = client.login(employee, PASSWORD);
        AuthTestClient.Session second = AuthTestClient.session(client.refresh(first));

        assertRefused(first);

        assertThat(reuseAudits()).isEqualTo(1);
        assertThat(reasons(second)).isEqualTo("REUSE_DETECTED,ROTATED");
        // The whole family ended; the newest token, revoked by the detection, is refused without
        // raising a second alarm.
        assertRefused(second);
        assertThat(client.meStatus(second)).isEqualTo(401);
        assertThat(reuseAudits()).isEqualTo(1);
    }

    @Test
    void aSessionRevokedFromTheSessionsListIsNotReuse() throws Exception {
        AuthTestClient.Session current = client.login(employee, PASSWORD);
        AuthTestClient.Session other = client.login(employee, PASSWORD);
        AuthTestClient.Session third = client.login(employee, PASSWORD);

        mvc.perform(
                        delete("/api/v1/me/sessions/" + sessionId(other))
                                .header("Authorization", "Bearer " + current.accessToken()))
                .andReturn();
        mvc.perform(
                        post("/api/v1/me/sessions/revoke-others")
                                .header("Authorization", "Bearer " + current.accessToken()))
                .andReturn();

        assertRefused(other);
        assertRefused(third);
        assertThat(reasons(other)).isEqualTo("SESSION_REVOKED");
        assertThat(reasons(third)).isEqualTo("SESSION_REVOKED");
        assertThat(reuseAudits()).isZero();
    }

    @Test
    void theCurrentSessionRevokedFromTheSessionsListIsNotReuse() throws Exception {
        AuthTestClient.Session current = client.login(employee, PASSWORD);

        mvc.perform(
                        delete("/api/v1/me/sessions/" + sessionId(current))
                                .header("Authorization", "Bearer " + current.accessToken()))
                .andReturn();

        assertRefused(current);
        assertThat(reasons(current)).isEqualTo("SESSION_REVOKED");
        assertThat(reuseAudits()).isZero();
    }

    @Test
    void aSessionEndedByAPasswordChangeIsNotReuse() throws Exception {
        AuthTestClient.Session changer = client.login(employee, PASSWORD);
        AuthTestClient.Session other = client.login(employee, PASSWORD);

        MvcResult change =
                mvc.perform(
                                post("/api/v1/me/password")
                                        .header("Authorization", "Bearer " + changer.accessToken())
                                        .contentType("application/json")
                                        .content(
                                                AuthTestClient.JSON.writeValueAsString(
                                                        Map.of(
                                                                "currentPassword",
                                                                PASSWORD,
                                                                "newPassword",
                                                                NEW_PASSWORD,
                                                                "confirmPassword",
                                                                NEW_PASSWORD))))
                        .andReturn();
        assertThat(change.getResponse().getStatus()).isEqualTo(204);

        assertRefused(other);
        assertThat(reasons(other)).isEqualTo("PASSWORD_CHANGED");
        assertThat(reuseAudits()).isZero();
    }

    @Test
    void aSessionEndedByAPasswordResetIsNotReuse() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);
        mvc.perform(
                        post("/api/v1/auth/forgot-password")
                                .contentType("application/json")
                                .content(
                                        AuthTestClient.JSON.writeValueAsString(
                                                Map.of(
                                                        "organization",
                                                        employee.organizationLoginKey(),
                                                        "email",
                                                        employee.email()))))
                .andReturn();
        String code =
                jdbc.queryForObject(
                        "SELECT payload -> 'attributes' ->> 'resetCode' FROM email_outbox"
                                + " WHERE organization_id = ? AND type = 'PASSWORD_RESET'",
                        String.class,
                        employee.organizationId());
        MvcResult reset =
                mvc.perform(
                                post("/api/v1/auth/reset-password")
                                        .contentType("application/json")
                                        .content(
                                                AuthTestClient.JSON.writeValueAsString(
                                                        Map.of(
                                                                "token",
                                                                code,
                                                                "password",
                                                                NEW_PASSWORD,
                                                                "confirmPassword",
                                                                NEW_PASSWORD))))
                        .andReturn();
        assertThat(reset.getResponse().getStatus()).isEqualTo(204);

        assertRefused(session);
        assertThat(reasons(session)).isEqualTo("PASSWORD_RESET");
        assertThat(reuseAudits()).isZero();
    }

    @Test
    void aSessionEndedByDeactivationIsNotReuse() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);
        // Deactivation itself arrives later in b2-6; its revoke reason is what matters here.
        jdbc.update(
                "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                        + " revoke_reason = 'DEACTIVATED' WHERE family_id = ? AND NOT revoked",
                sessionId(session));

        assertRefused(session);
        assertThat(reasons(session)).isEqualTo("DEACTIVATED");
        assertThat(reuseAudits()).isZero();
    }

    @Test
    void aTokenPresentedAfterLogoutIsStillReuse() throws Exception {
        AuthTestClient.Session session = client.login(employee, PASSWORD);
        client.logout(session);

        // The browser that logged out dropped the cookie; whoever still presents it copied it
        // (b2-3 behaviour, kept).
        assertRefused(session);
        assertThat(reuseAudits()).isEqualTo(1);
        assertThat(reasons(session)).isEqualTo("LOGOUT");
    }
}
