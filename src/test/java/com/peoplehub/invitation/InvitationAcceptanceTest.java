package com.peoplehub.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.SignedJWT;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.TestIdentities;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
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
 * Invitation preview and acceptance end to end (b2-4, B2-4/O4, O5; Spec 2.1.5, D24): the invitee
 * sees who invited them, sets their own password, becomes active and signs in normally. Every
 * unusable token gets the identical generic 404. Runs on a controllable clock for expiry.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class InvitationAcceptanceTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;
    @Autowired private MutableClock clock;
    @Autowired private InvitationAcceptanceService acceptanceService;

    private TestIdentities.Organization org;
    private TestIdentities.Employee superAdmin;

    @BeforeEach
    void anOrganizationWithASuperAdmin() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        org = TestIdentities.activeOrganization(jdbc);
        superAdmin = TestIdentities.activeEmployee(jdbc, org, "SUPER_ADMIN", null);
    }

    /** Invites through the real endpoint and returns the raw token from the invitation email. */
    private Invited invite(String path, String name) throws Exception {
        String email = "invitee-" + UUID.randomUUID() + "@example.com";
        UUID session = TestIdentities.activeSession(jdbc, superAdmin, clock.instant());
        String accessToken =
                issuer.issue(superAdmin.id(), org.id(), "SUPER_ADMIN", session).value();
        MvcResult result =
                mvc.perform(
                                post(path)
                                        .header("Authorization", "Bearer " + accessToken)
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of("name", name, "email", email))))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> body = body(result);
        String token =
                JSON.readTree(
                                jdbc.queryForObject(
                                        "SELECT payload::text FROM email_outbox"
                                                + " WHERE organization_id = ? AND recipient = ?",
                                        String.class,
                                        org.id(),
                                        email))
                        .get("attributes")
                        .get("inviteCode")
                        .asString();
        return new Invited(
                email,
                token,
                UUID.fromString((String) body.get("employeeId")),
                UUID.fromString((String) body.get("invitationId")));
    }

    private Invited inviteEmployee() throws Exception {
        return invite("/api/v1/admin/employees/invite", "Riya Sharma");
    }

    private record Invited(String email, String token, UUID employeeId, UUID invitationId) {}

    private MvcResult preview(String token) throws Exception {
        return mvc.perform(get("/api/v1/public/invitations/" + token + "/preview")).andReturn();
    }

    private MvcResult accept(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(
                        post("/api/v1/public/invitations/" + token + "/accept")
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult accept(String token) throws Exception {
        return accept(token, Map.of("password", PASSWORD, "confirmPassword", PASSWORD));
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "organization",
                                                        org.loginKey(),
                                                        "email",
                                                        email,
                                                        "password",
                                                        password))))
                .andReturn();
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private Map<String, Object> employee(UUID id) {
        return jdbc.queryForMap(
                "SELECT status, role, password_hash, organization_id FROM employee WHERE id = ?",
                id);
    }

    private Map<String, Object> invitation(UUID id) {
        return jdbc.queryForMap(
                "SELECT consumed_at, revoked_at FROM employee_invitation WHERE id = ?", id);
    }

    /** The one rejection for any unusable token: 404, generic detail, nothing else. */
    private Map<String, Object> assertInvalid(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        Map<String, Object> body = body(result);
        assertThat(body)
                .containsOnlyKeys("type", "title", "status", "detail", "instance", "correlationId")
                .containsEntry("type", "urn:peoplehub:problem:not-found")
                .containsEntry("detail", InvitationAcceptanceService.INVALID);
        body.remove("instance");
        body.remove("correlationId");
        return body;
    }

    // ---- preview ----

    @Test
    void thePreviewShowsTheOrganizationRoleAndTheInviteesOwnDetailsOnly() throws Exception {
        Invited invited = inviteEmployee();

        MvcResult result = preview(invited.token());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> body = body(result);
        assertThat(body)
                .containsOnlyKeys("organizationName", "role", "name", "email", "expiresAt")
                .containsEntry("organizationName", "Acme " + org.loginKey())
                .containsEntry("role", "EMPLOYEE")
                .containsEntry("name", "Riya Sharma")
                .containsEntry("email", invited.email());
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(org.id().toString())
                .doesNotContain(invited.employeeId().toString())
                .doesNotContain(superAdmin.email());
    }

    @Test
    void thePreviewChangesNothing() throws Exception {
        Invited invited = inviteEmployee();

        preview(invited.token());
        preview(invited.token());

        assertThat(invitation(invited.invitationId()).get("consumed_at")).isNull();
        assertThat(employee(invited.employeeId())).containsEntry("status", "INVITED");
        assertThat(accept(invited.token()).getResponse().getStatus()).isEqualTo(204);
    }

    // ---- acceptance ----

    @Test
    void acceptingSetsTheInviteesOwnPasswordAndTheyThenSignInNormally() throws Exception {
        Invited invited = inviteEmployee();

        MvcResult result = accept(invited.token());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        // No session is opened by acceptance (B2-4/O5).
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();

        Map<String, Object> row = employee(invited.employeeId());
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("role", "EMPLOYEE");
        assertThat((String) row.get("password_hash")).isNotBlank().doesNotContain(PASSWORD);
        assertThat(invitation(invited.invitationId()).get("consumed_at")).isNotNull();

        MvcResult login = login(invited.email(), PASSWORD);
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        SignedJWT jwt = SignedJWT.parse((String) body(login).get("accessToken"));
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(invited.employeeId().toString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("EMPLOYEE");
    }

    @Test
    void aDirectlyInvitedAdminBecomesAnActiveAdminWithNoMfaStep() throws Exception {
        Invited invited = invite("/api/v1/super-admin/admins/invite", "Ada Admin");

        assertThat(accept(invited.token()).getResponse().getStatus()).isEqualTo(204);

        assertThat(employee(invited.employeeId()))
                .containsEntry("status", "ACTIVE")
                .containsEntry("role", "ADMIN");
        MvcResult login = login(invited.email(), PASSWORD);
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(
                        SignedJWT.parse((String) body(login).get("accessToken"))
                                .getJWTClaimsSet()
                                .getStringClaim("role"))
                .isEqualTo("ADMIN");
    }

    @Test
    void acceptanceIsAuditedAsTheInvitee() throws Exception {
        Invited invited = inviteEmployee();

        accept(invited.token());

        Map<String, Object> audit =
                jdbc.queryForMap(
                        "SELECT actor_id, target_type, target_id, details::text AS details"
                                + " FROM audit_log WHERE organization_id = ?"
                                + " AND action = 'INVITATION_ACCEPTED'",
                        org.id());
        assertThat(audit)
                .containsEntry("actor_id", invited.employeeId().toString())
                .containsEntry("target_type", "EMPLOYEE_INVITATION")
                .containsEntry("target_id", invited.invitationId().toString());
        assertThat((String) audit.get("details"))
                .doesNotContain(invited.email())
                .doesNotContain(PASSWORD);
    }

    @Test
    void theRoleAndOrganizationCannotBeChangedByTheInvitee() throws Exception {
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);
        Invited invited = inviteEmployee();
        Map<String, Object> tampered = new HashMap<>();
        tampered.put("password", PASSWORD);
        tampered.put("confirmPassword", PASSWORD);
        tampered.put("role", "SUPER_ADMIN");
        tampered.put("organizationId", other.id().toString());
        tampered.put("email", "someone-else@example.com");

        assertThat(accept(invited.token(), tampered).getResponse().getStatus()).isEqualTo(204);

        assertThat(employee(invited.employeeId()))
                .containsEntry("role", "EMPLOYEE")
                .containsEntry("organization_id", org.id());
    }

    // ---- password rules ----

    @Test
    void aWeakOrMismatchedPasswordIsRejectedAndTheInvitationStaysUsable() throws Exception {
        Invited invited = inviteEmployee();

        MvcResult weak =
                accept(invited.token(), Map.of("password", "short", "confirmPassword", "short"));
        MvcResult mismatch =
                accept(
                        invited.token(),
                        Map.of("password", PASSWORD, "confirmPassword", PASSWORD + "x"));
        MvcResult blank = accept(invited.token(), Map.of("password", "", "confirmPassword", ""));

        assertThat(weak.getResponse().getStatus()).isEqualTo(400);
        assertThat(weak.getResponse().getContentAsString())
                .contains("\"field\":\"password\"")
                .doesNotContain("short\"");
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(400);
        assertThat(mismatch.getResponse().getContentAsString())
                .contains("\"field\":\"confirmPassword\"")
                .doesNotContain(PASSWORD);
        assertThat(blank.getResponse().getStatus()).isEqualTo(400);

        assertThat(employee(invited.employeeId())).containsEntry("status", "INVITED");
        assertThat(invitation(invited.invitationId()).get("consumed_at")).isNull();
        assertThat(accept(invited.token()).getResponse().getStatus()).isEqualTo(204);
    }

    // ---- one generic answer for every unusable token (B2-4/O4) ----

    @Test
    void everyUnusableTokenGetsTheSameAnswerFromPreviewAndAccept() throws Exception {
        Invited used = inviteEmployee();
        accept(used.token());

        Invited revoked = inviteEmployee();
        jdbc.update(
                "UPDATE employee_invitation SET revoked_at = now() WHERE id = ?",
                revoked.invitationId());

        Invited deactivated = inviteEmployee();
        jdbc.update(
                "UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?",
                deactivated.employeeId());

        List<String> unusable =
                List.of(
                        "not-a-real-token-" + UUID.randomUUID(),
                        "x".repeat(300),
                        used.token(),
                        revoked.token(),
                        deactivated.token());

        Map<String, Object> first = null;
        for (String token : unusable) {
            for (MvcResult result : List.of(preview(token), accept(token))) {
                Map<String, Object> body = assertInvalid(result);
                if (first == null) {
                    first = body;
                }
                assertThat(body).isEqualTo(first);
            }
        }
    }

    @Test
    void anInvitationExpiresAfterSevenDays() throws Exception {
        Invited invited = inviteEmployee();

        clock.advance(Duration.ofDays(7).minusSeconds(1));
        assertThat(preview(invited.token()).getResponse().getStatus()).isEqualTo(200);

        clock.advance(Duration.ofSeconds(2));
        assertInvalid(preview(invited.token()));
        assertInvalid(accept(invited.token()));
        assertThat(employee(invited.employeeId())).containsEntry("status", "INVITED");
    }

    @Test
    void anInvitationOfASuspendedOrganizationCannotBeAccepted() throws Exception {
        Invited invited = inviteEmployee();
        jdbc.update("UPDATE organization SET status = 'SUSPENDED' WHERE id = ?", org.id());

        assertInvalid(preview(invited.token()));
        assertInvalid(accept(invited.token()));
    }

    // ---- single use ----

    @Test
    void anInvitationCanBeAcceptedOnlyOnce() throws Exception {
        Invited invited = inviteEmployee();
        assertThat(accept(invited.token()).getResponse().getStatus()).isEqualTo(204);
        String hashAfterFirst = (String) employee(invited.employeeId()).get("password_hash");

        assertInvalid(
                accept(
                        invited.token(),
                        Map.of(
                                "password",
                                "Another-" + PASSWORD,
                                "confirmPassword",
                                "Another-" + PASSWORD)));

        assertThat(employee(invited.employeeId())).containsEntry("password_hash", hashAfterFirst);
        assertThat(login(invited.email(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void twoSimultaneousAcceptancesActivateOnlyOnce() throws Exception {
        Invited invited = inviteEmployee();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> acceptOnce =
                () -> {
                    ActorId.set(ActorId.ANONYMOUS);
                    try {
                        start.await();
                        acceptanceService.accept(
                                invited.token(), new AcceptInvitationRequest(PASSWORD, PASSWORD));
                        return true;
                    } catch (ApiProblemException e) {
                        return false;
                    } finally {
                        ActorId.clear();
                    }
                };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            results.add(pool.submit(acceptOnce));
            results.add(pool.submit(acceptOnce));
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                succeeded += result.get() ? 1 : 0;
            }

            assertThat(succeeded).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM audit_log WHERE organization_id = ?"
                                            + " AND action = 'INVITATION_ACCEPTED'",
                                    Integer.class,
                                    org.id()))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- the token stays out of logs ----

    @Test
    void theTokenNeverAppearsInTheApplicationLogs(CapturedOutput output) throws Exception {
        Invited invited = inviteEmployee();

        preview(invited.token());
        accept(invited.token(), Map.of("password", "short", "confirmPassword", "short"));
        accept(invited.token());

        String applicationLogs =
                output.getAll()
                        .lines()
                        .filter(line -> line.startsWith("{\"@timestamp\""))
                        .reduce("", (all, line) -> all + line + "\n");
        assertThat(applicationLogs)
                .contains("/api/v1/public/invitations/{token}/accept")
                .doesNotContain(invited.token())
                .doesNotContain(PASSWORD)
                .doesNotContain(invited.email());
    }

    @Test
    void acceptingWithoutAnyTokenReachesNothing() {
        assertThatThrownBy(() -> acceptanceService.preview(null))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> acceptanceService.preview(" "))
                .isInstanceOf(ApiProblemException.class);
    }
}
