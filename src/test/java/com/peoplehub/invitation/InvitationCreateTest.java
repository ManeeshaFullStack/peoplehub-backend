package com.peoplehub.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creating invitations (b2-4, B2-4/O1, O2, O7, O10; Spec 2.1.5, D24, D25): who may invite whom,
 * what is written (employee, invitation, outbox email, audit row), conflicts, and the tenant
 * boundary.
 */
@IntegrationTest
@AutoConfigureMockMvc
class InvitationCreateTest {

    private static final String INVITE_EMPLOYEE = "/api/v1/admin/employees/invite";
    private static final String INVITE_ADMIN = "/api/v1/super-admin/admins/invite";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;

    private TestIdentities.Organization org;
    private TestIdentities.Employee superAdmin;
    private TestIdentities.Employee admin;
    private TestIdentities.Employee employee;

    @BeforeEach
    void anOrganizationWithOnePersonPerRole() {
        org = TestIdentities.activeOrganization(jdbc);
        superAdmin = TestIdentities.activeEmployee(jdbc, org, "SUPER_ADMIN", null);
        admin = TestIdentities.activeEmployee(jdbc, org, "ADMIN", null);
        employee = TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
    }

    private String tokenFor(TestIdentities.Employee caller) {
        UUID session = TestIdentities.activeSession(jdbc, caller, Instant.now());
        return issuer.issue(caller.id(), caller.organizationId(), caller.role(), session).value();
    }

    private static String inviteeEmail() {
        return "invitee-" + UUID.randomUUID() + "@example.com";
    }

    private MvcResult invite(String path, TestIdentities.Employee caller, Map<String, Object> body)
            throws Exception {
        return mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + tokenFor(caller))
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(body)))
                .andReturn();
    }

    private static Map<String, Object> request(String name, String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("email", email);
        return body;
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private Map<String, Object> employeeRow(String emailNormalized) {
        return jdbc.queryForMap(
                "SELECT id, organization_id, name, email, status, role, password_hash,"
                        + " employee_code, join_date FROM employee WHERE organization_id = ?"
                        + " AND email_normalized = ?",
                org.id(),
                emailNormalized);
    }

    private List<Map<String, Object>> invitations(String emailNormalized) {
        return jdbc.queryForList(
                "SELECT id, intended_role, token_hash, inviter_employee_id, expires_at,"
                        + " consumed_at, revoked_at FROM employee_invitation"
                        + " WHERE organization_id = ? AND email_normalized = ? ORDER BY created_at",
                org.id(),
                emailNormalized);
    }

    private Map<String, Object> invitationEmail(String recipient) {
        return jdbc.queryForMap(
                "SELECT recipient, type, payload::text AS payload FROM email_outbox"
                        + " WHERE organization_id = ? AND recipient = ?",
                org.id(),
                recipient);
    }

    // ---- who may invite whom ----

    @Test
    void anAdminInvitesAnEmployee() throws Exception {
        String email = inviteeEmail();
        Map<String, Object> request = request("Riya Sharma", email);
        request.put("employeeCode", "E-" + UUID.randomUUID().toString().substring(0, 8));
        request.put("joinDate", "2026-10-01");

        MvcResult result = invite(INVITE_EMPLOYEE, admin, request);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> body = body(result);
        assertThat(body)
                .containsOnlyKeys("invitationId", "employeeId", "role", "expiresAt")
                .containsEntry("role", "EMPLOYEE");

        Map<String, Object> row = employeeRow(email);
        assertThat(row)
                .containsEntry("id", UUID.fromString((String) body.get("employeeId")))
                .containsEntry("name", "Riya Sharma")
                .containsEntry("status", "INVITED")
                .containsEntry("role", "EMPLOYEE")
                .containsEntry("employee_code", request.get("employeeCode"));
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("join_date").toString()).isEqualTo("2026-10-01");

        List<Map<String, Object>> invitations = invitations(email);
        assertThat(invitations).singleElement();
        assertThat(invitations.get(0))
                .containsEntry("id", UUID.fromString((String) body.get("invitationId")))
                .containsEntry("intended_role", "EMPLOYEE")
                .containsEntry("inviter_employee_id", admin.id());
        assertThat(invitations.get(0).get("consumed_at")).isNull();
    }

    @Test
    void aSuperAdminInvitesEmployeesAndAdmins() throws Exception {
        String employeeEmail = inviteeEmail();
        String adminEmail = inviteeEmail();

        assertThat(
                        invite(INVITE_EMPLOYEE, superAdmin, request("Emp One", employeeEmail))
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);
        MvcResult adminInvite = invite(INVITE_ADMIN, superAdmin, request("Ada Admin", adminEmail));

        assertThat(adminInvite.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(adminInvite)).containsEntry("role", "ADMIN");
        assertThat(employeeRow(adminEmail))
                .containsEntry("role", "ADMIN")
                .containsEntry("status", "INVITED");
    }

    @Test
    void anAdminCannotInviteAnAdminAndAnEmployeeCannotInviteAnyone() throws Exception {
        String email = inviteeEmail();

        MvcResult adminInvitesAdmin = invite(INVITE_ADMIN, admin, request("X", email));
        MvcResult employeeInvitesEmployee = invite(INVITE_EMPLOYEE, employee, request("X", email));
        MvcResult employeeInvitesAdmin = invite(INVITE_ADMIN, employee, request("X", email));

        for (MvcResult result :
                List.of(adminInvitesAdmin, employeeInvitesEmployee, employeeInvitesAdmin)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(result)).containsEntry("type", "urn:peoplehub:problem:forbidden");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM employee WHERE email_normalized = ?",
                                Integer.class,
                                email))
                .isZero();
    }

    @Test
    void invitingNeedsAnAccessToken() throws Exception {
        for (String path : List.of(INVITE_EMPLOYEE, INVITE_ADMIN)) {
            MvcResult result =
                    mvc.perform(
                                    post(path)
                                            .contentType("application/json")
                                            .content(
                                                    JSON.writeValueAsString(
                                                            request("X", inviteeEmail()))))
                            .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
        }
    }

    // ---- what is written ----

    @Test
    void theInvitationEmailCarriesTheCodeLoginKeyAndRoleAndTheTokenIsStoredOnlyHashed()
            throws Exception {
        String email = inviteeEmail();

        invite(INVITE_EMPLOYEE, admin, request("Riya Sharma", email));

        Map<String, Object> outbox = invitationEmail(email);
        assertThat(outbox).containsEntry("type", "EMPLOYEE_INVITED");
        Map<String, Object> attributes =
                JSON
                        .readValue(
                                (String) outbox.get("payload"),
                                new TypeReference<Map<String, Object>>() {})
                        .entrySet()
                        .stream()
                        .filter(e -> e.getKey().equals("attributes"))
                        .map(e -> (Map<String, Object>) castMap(e.getValue()))
                        .findFirst()
                        .orElseThrow();
        assertThat(attributes)
                .containsOnlyKeys(
                        "appName", "firstName", "organizationLoginKey", "role", "inviteCode")
                .containsEntry("firstName", "Riya")
                .containsEntry("organizationLoginKey", org.loginKey())
                .containsEntry("role", "EMPLOYEE");

        String rawToken = (String) attributes.get("inviteCode");
        assertThat(rawToken).hasSize(64);
        Map<String, Object> invitation = invitations(email).get(0);
        assertThat(invitation.get("token_hash")).isNotEqualTo(rawToken);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM employee_invitation WHERE token_hash = ?",
                                Integer.class,
                                rawToken))
                .isZero();
    }

    @Test
    void theResponseNeverContainsTheToken() throws Exception {
        String email = inviteeEmail();

        MvcResult result = invite(INVITE_EMPLOYEE, admin, request("Riya", email));

        String rawToken =
                JSON.readTree((String) invitationEmail(email).get("payload"))
                        .get("attributes")
                        .get("inviteCode")
                        .asString();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(rawToken);
    }

    @Test
    void theInvitationIsAuditedWithIdsOnly() throws Exception {
        String email = inviteeEmail();

        Map<String, Object> body = body(invite(INVITE_EMPLOYEE, admin, request("Riya", email)));

        Map<String, Object> audit =
                jdbc.queryForMap(
                        "SELECT actor_id, target_type, target_id, details::text AS details"
                                + " FROM audit_log WHERE organization_id = ?"
                                + " AND action = 'INVITATION_CREATED'",
                        org.id());
        assertThat(audit)
                .containsEntry("actor_id", admin.id().toString())
                .containsEntry("target_type", "EMPLOYEE_INVITATION")
                .containsEntry("target_id", body.get("invitationId"));
        assertThat((String) audit.get("details"))
                .contains((String) body.get("employeeId"))
                .contains("EMPLOYEE")
                .doesNotContain(email)
                .doesNotContain("Riya");
    }

    @Test
    void anEmployeeCodeIsGeneratedWhenNoneIsGiven() throws Exception {
        String email = inviteeEmail();

        invite(INVITE_EMPLOYEE, admin, request("Riya", email));

        assertThat((String) employeeRow(email).get("employee_code")).matches("EMP-[A-Z2-9]{8}");
    }

    @Test
    void anInvitedEmployeeCannotSignInBeforeAccepting() throws Exception {
        String email = inviteeEmail();
        invite(INVITE_EMPLOYEE, admin, request("Riya", email));

        MvcResult login =
                mvc.perform(
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
                                                                "anything-at-all"))))
                        .andReturn();

        assertThat(login.getResponse().getStatus()).isEqualTo(401);
    }

    // ---- conflicts (B2-4/O7) ----

    @Test
    void anExistingActiveEmployeeCannotBeInvited() throws Exception {
        MvcResult result = invite(INVITE_EMPLOYEE, admin, request("Again", employee.email()));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result)).containsEntry("type", "urn:peoplehub:problem:conflict");
    }

    @Test
    void aPendingInvitationBlocksASecondInvite() throws Exception {
        String email = inviteeEmail();
        invite(INVITE_EMPLOYEE, admin, request("Riya", email));

        MvcResult again = invite(INVITE_EMPLOYEE, admin, request("Riya", email.toUpperCase()));

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(invitations(email)).hasSize(1);
    }

    @Test
    void anInvitedEmployeeWithNoOpenInvitationIsInvitedAgainOnTheSameRow() throws Exception {
        String email = inviteeEmail();
        Map<String, Object> first = body(invite(INVITE_EMPLOYEE, admin, request("Riya", email)));
        jdbc.update(
                "UPDATE employee_invitation SET revoked_at = now() WHERE id = ?",
                UUID.fromString((String) first.get("invitationId")));

        MvcResult again = invite(INVITE_EMPLOYEE, admin, request("Someone Else", email));

        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(again)).containsEntry("employeeId", first.get("employeeId"));
        assertThat(invitations(email)).hasSize(2);
        // The existing row keeps its name.
        assertThat(employeeRow(email)).containsEntry("name", "Riya");
    }

    @Test
    void reinvitingWithADifferentRoleIsAConflict() throws Exception {
        String email = inviteeEmail();
        Map<String, Object> first = body(invite(INVITE_EMPLOYEE, admin, request("Riya", email)));
        jdbc.update(
                "UPDATE employee_invitation SET revoked_at = now() WHERE id = ?",
                UUID.fromString((String) first.get("invitationId")));

        MvcResult asAdmin = invite(INVITE_ADMIN, superAdmin, request("Riya", email));

        assertThat(asAdmin.getResponse().getStatus()).isEqualTo(409);
        assertThat(employeeRow(email)).containsEntry("role", "EMPLOYEE");
    }

    @Test
    void aDuplicateEmployeeCodeIsAConflict() throws Exception {
        Map<String, Object> request = request("Riya", inviteeEmail());
        request.put(
                "employeeCode",
                jdbc.queryForObject(
                        "SELECT employee_code FROM employee WHERE id = ?",
                        String.class,
                        employee.id()));

        MvcResult result = invite(INVITE_EMPLOYEE, admin, request);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void invalidInputIsAValidationError() throws Exception {
        Map<String, Object> request = request(" ", "not-an-email");
        request.put("employeeCode", "has spaces");

        MvcResult result = invite(INVITE_EMPLOYEE, admin, request);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .contains("name")
                .contains("email")
                .contains("employeeCode")
                .doesNotContain("not-an-email");
    }

    // ---- tenant boundary ----

    @Test
    void theOrganizationAlwaysComesFromTheCallerNeverFromTheRequest() throws Exception {
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);
        String email = inviteeEmail();
        Map<String, Object> request = request("Riya", email);
        request.put("organizationId", other.id().toString());
        request.put("role", "SUPER_ADMIN");

        MvcResult result = invite(INVITE_EMPLOYEE, admin, request);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(employeeRow(email))
                .containsEntry("organization_id", org.id())
                .containsEntry("role", "EMPLOYEE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM employee WHERE organization_id = ?",
                                Integer.class,
                                other.id()))
                .isZero();
    }

    @Test
    void theSameEmailCanBeInvitedIntoTwoOrganizations() throws Exception {
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee otherAdmin =
                TestIdentities.activeEmployee(jdbc, other, "ADMIN", null);
        String email = inviteeEmail();

        assertThat(invite(INVITE_EMPLOYEE, admin, request("Riya", email)).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(
                        invite(INVITE_EMPLOYEE, otherAdmin, request("Riya", email))
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
