package com.peoplehub.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resending and revoking invitations (b2-4, B2-4/O7, O8, O11): who may do it, what changes, and the
 * tenant boundary (another organization's invitation is not found). Runs on a controllable clock so
 * an expired invitation can be resent.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
class InvitationResendRevokeTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;
    @Autowired private MutableClock clock;

    private TestIdentities.Organization org;
    private TestIdentities.Employee superAdmin;
    private TestIdentities.Employee admin;
    private TestIdentities.Employee employee;

    @BeforeEach
    void anOrganizationWithOnePersonPerRole() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        org = TestIdentities.activeOrganization(jdbc);
        superAdmin = TestIdentities.activeEmployee(jdbc, org, "SUPER_ADMIN", null);
        admin = TestIdentities.activeEmployee(jdbc, org, "ADMIN", null);
        employee = TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
    }

    private String tokenFor(TestIdentities.Employee caller) {
        UUID session = TestIdentities.activeSession(jdbc, caller, clock.instant());
        return issuer.issue(caller.id(), caller.organizationId(), caller.role(), session).value();
    }

    private record Invited(String email, UUID invitationId, UUID employeeId) {}

    private Invited invite(String path, TestIdentities.Employee caller) throws Exception {
        String email = "invitee-" + UUID.randomUUID() + "@example.com";
        MvcResult result =
                mvc.perform(
                                post(path)
                                        .header("Authorization", "Bearer " + tokenFor(caller))
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of("name", "Riya", "email", email))))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> body = body(result);
        return new Invited(
                email,
                UUID.fromString((String) body.get("invitationId")),
                UUID.fromString((String) body.get("employeeId")));
    }

    private Invited inviteEmployee() throws Exception {
        return invite("/api/v1/admin/employees/invite", admin);
    }

    private Invited inviteAdmin() throws Exception {
        return invite("/api/v1/super-admin/admins/invite", superAdmin);
    }

    private MvcResult resend(TestIdentities.Employee caller, UUID invitationId) throws Exception {
        return mvc.perform(
                        post("/api/v1/admin/invitations/" + invitationId + "/resend")
                                .header("Authorization", "Bearer " + tokenFor(caller)))
                .andReturn();
    }

    private MvcResult revoke(TestIdentities.Employee caller, UUID invitationId) throws Exception {
        return mvc.perform(
                        post("/api/v1/admin/invitations/" + invitationId + "/revoke")
                                .header("Authorization", "Bearer " + tokenFor(caller)))
                .andReturn();
    }

    /** The raw tokens emailed to this address, oldest first. */
    private List<String> emailedTokens(String email) {
        return jdbc
                .queryForList(
                        "SELECT payload::text FROM email_outbox WHERE organization_id = ?"
                                + " AND recipient = ? ORDER BY id",
                        String.class,
                        org.id(),
                        email)
                .stream()
                .map(
                        payload ->
                                JSON.readTree(payload)
                                        .get("attributes")
                                        .get("inviteCode")
                                        .asString())
                .toList();
    }

    private int previewStatus(String token) throws Exception {
        return mvc.perform(get("/api/v1/public/invitations/" + token + "/preview"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int acceptStatus(String token) throws Exception {
        return mvc.perform(
                        post("/api/v1/public/invitations/" + token + "/accept")
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "password",
                                                        PASSWORD,
                                                        "confirmPassword",
                                                        PASSWORD))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private Map<String, Object> invitation(UUID id) {
        return jdbc.queryForMap(
                "SELECT consumed_at, revoked_at, expires_at, inviter_employee_id"
                        + " FROM employee_invitation WHERE id = ?",
                id);
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private List<String> auditActions() {
        return jdbc.queryForList(
                "SELECT action FROM audit_log WHERE organization_id = ? ORDER BY id",
                String.class,
                org.id());
    }

    // ---- resend ----

    @Test
    void aResendReplacesTheInvitationWithANewTokenAndEmail() throws Exception {
        Invited invited = inviteEmployee();
        String oldToken = emailedTokens(invited.email()).get(0);

        MvcResult result = resend(admin, invited.invitationId());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> body = body(result);
        assertThat(body)
                .containsOnlyKeys("invitationId", "employeeId", "role", "expiresAt")
                .containsEntry("employeeId", invited.employeeId().toString())
                .containsEntry("role", "EMPLOYEE");
        UUID newId = UUID.fromString((String) body.get("invitationId"));
        assertThat(newId).isNotEqualTo(invited.invitationId());

        assertThat(invitation(invited.invitationId()).get("revoked_at")).isNotNull();
        assertThat(invitation(newId))
                .containsEntry("inviter_employee_id", admin.id())
                .containsEntry("revoked_at", null)
                .containsEntry("consumed_at", null);

        List<String> tokens = emailedTokens(invited.email());
        assertThat(tokens).hasSize(2);
        String newToken = tokens.get(1);
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(oldToken)
                .doesNotContain(newToken);

        assertThat(previewStatus(oldToken)).isEqualTo(404);
        assertThat(acceptStatus(oldToken)).isEqualTo(404);
        assertThat(acceptStatus(newToken)).isEqualTo(204);
        assertThat(auditActions())
                .containsExactly("INVITATION_CREATED", "INVITATION_RESENT", "INVITATION_ACCEPTED");
    }

    @Test
    void anExpiredInvitationCanBeResentWithAFullNewLifetime() throws Exception {
        Invited invited = inviteEmployee();
        clock.advance(Duration.ofDays(8));
        assertThat(previewStatus(emailedTokens(invited.email()).get(0))).isEqualTo(404);

        MvcResult result = resend(admin, invited.invitationId());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        UUID newId = UUID.fromString((String) body(result).get("invitationId"));
        assertThat(((Timestamp) invitation(newId).get("expires_at")).toInstant())
                .isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(acceptStatus(emailedTokens(invited.email()).get(1))).isEqualTo(204);
    }

    // ---- revoke ----

    @Test
    void aRevokedInvitationStopsWorkingAtOnceAndTheEmployeeStaysInvited() throws Exception {
        Invited invited = inviteEmployee();
        String token = emailedTokens(invited.email()).get(0);

        MvcResult result = revoke(admin, invited.invitationId());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(invitation(invited.invitationId()).get("revoked_at")).isNotNull();
        assertThat(previewStatus(token)).isEqualTo(404);
        assertThat(acceptStatus(token)).isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM employee WHERE id = ?",
                                String.class,
                                invited.employeeId()))
                .isEqualTo("INVITED");

        Map<String, Object> audit =
                jdbc.queryForMap(
                        "SELECT actor_id, target_id, details::text AS details FROM audit_log"
                                + " WHERE organization_id = ? AND action = 'INVITATION_REVOKED'",
                        org.id());
        assertThat(audit)
                .containsEntry("actor_id", admin.id().toString())
                .containsEntry("target_id", invited.invitationId().toString());
        assertThat((String) audit.get("details")).doesNotContain(invited.email());
    }

    // ---- only open invitations (409) ----

    @Test
    void anAcceptedOrRevokedInvitationCannotBeResentOrRevoked() throws Exception {
        Invited accepted = inviteEmployee();
        assertThat(acceptStatus(emailedTokens(accepted.email()).get(0))).isEqualTo(204);
        Invited revoked = inviteEmployee();
        assertThat(revoke(admin, revoked.invitationId()).getResponse().getStatus()).isEqualTo(204);

        for (UUID id : List.of(accepted.invitationId(), revoked.invitationId())) {
            for (MvcResult result : List.of(resend(admin, id), revoke(admin, id))) {
                assertThat(result.getResponse().getStatus()).isEqualTo(409);
                assertThat(body(result)).containsEntry("type", "urn:peoplehub:problem:conflict");
            }
        }
        assertThat(emailedTokens(accepted.email())).hasSize(1);
        assertThat(emailedTokens(revoked.email())).hasSize(1);
    }

    // ---- who may (B2-4/O8) ----

    @Test
    void anAdminCannotResendOrRevokeAnAdminInvitationButASuperAdminCan() throws Exception {
        Invited adminInvite = inviteAdmin();

        assertThat(resend(admin, adminInvite.invitationId()).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(revoke(admin, adminInvite.invitationId()).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(invitation(adminInvite.invitationId()).get("revoked_at")).isNull();

        assertThat(resend(superAdmin, adminInvite.invitationId()).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void aSuperAdminCanResendAndRevokeEmployeeInvitationsToo() throws Exception {
        Invited first = inviteEmployee();
        Invited second = inviteEmployee();

        assertThat(resend(superAdmin, first.invitationId()).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(revoke(superAdmin, second.invitationId()).getResponse().getStatus())
                .isEqualTo(204);
    }

    @Test
    void anEmployeeCanNeitherResendNorRevoke() throws Exception {
        Invited invited = inviteEmployee();

        MvcResult resent = resend(employee, invited.invitationId());
        MvcResult revoked = revoke(employee, invited.invitationId());

        assertThat(resent.getResponse().getStatus()).isEqualTo(403);
        assertThat(revoked.getResponse().getStatus()).isEqualTo(403);
        assertThat(invitation(invited.invitationId()).get("revoked_at")).isNull();
    }

    @Test
    void resendAndRevokeNeedAnAccessToken() throws Exception {
        Invited invited = inviteEmployee();

        for (String action : List.of("resend", "revoke")) {
            MvcResult result =
                    mvc.perform(
                                    post(
                                            "/api/v1/admin/invitations/"
                                                    + invited.invitationId()
                                                    + "/"
                                                    + action))
                            .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
        }
    }

    // ---- tenant boundary: not found, never forbidden (D22) ----

    @Test
    void anotherOrganizationsInvitationIsNotFoundAndUntouched() throws Exception {
        Invited ours = inviteEmployee();
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee otherSuperAdmin =
                TestIdentities.activeEmployee(jdbc, other, "SUPER_ADMIN", null);

        MvcResult resent = resend(otherSuperAdmin, ours.invitationId());
        MvcResult revoked = revoke(otherSuperAdmin, ours.invitationId());
        MvcResult unknown = resend(admin, UUID.randomUUID());

        for (MvcResult result : List.of(resent, revoked, unknown)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(body(result))
                    .containsEntry("type", "urn:peoplehub:problem:not-found")
                    .containsEntry("detail", "The requested resource was not found.");
        }
        // The two 404s look the same whether the invitation exists elsewhere or nowhere.
        Map<String, Object> foreign = body(resent);
        Map<String, Object> missing = body(unknown);
        foreign.remove("instance");
        foreign.remove("correlationId");
        missing.remove("instance");
        missing.remove("correlationId");
        assertThat(foreign).isEqualTo(missing);

        assertThat(invitation(ours.invitationId()).get("revoked_at")).isNull();
        assertThat(emailedTokens(ours.email())).hasSize(1);
    }

    @Test
    void aMalformedIdIsAValidationError() throws Exception {
        MvcResult result =
                mvc.perform(
                                post("/api/v1/admin/invitations/not-a-uuid/revoke")
                                        .header("Authorization", "Bearer " + tokenFor(admin)))
                        .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("not-a-uuid");
    }
}
