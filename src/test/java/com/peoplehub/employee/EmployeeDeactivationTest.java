package com.peoplehub.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /admin/employees/{id}/deactivate} and {@code /reactivate} end to end (b2-6,
 * B2-6/7-B2-6/11, B2-6/13-B2-6/16; Spec 2.1.7, 3.2, 3.3, D26): who may act on whom, the state
 * rules, the optional exit date in the organization's timezone, everything deactivation revokes and
 * everything it keeps, reactivation, audit rows and the tenant boundary.
 */
@IntegrationTest
@AutoConfigureMockMvc
class EmployeeDeactivationTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private Clock clock;

    /** An employee with a password, and the tokens of one signed-in session. */
    private record Person(
            TestIdentities.Employee employee,
            String accessToken,
            String refreshToken,
            String csrf) {
        UUID id() {
            return employee.id();
        }
    }

    private TestIdentities.Employee create(TestIdentities.Organization org, String role) {
        return TestIdentities.activeEmployee(jdbc, org, role, passwordHasher.hash(PASSWORD));
    }

    private MvcResult login(TestIdentities.Employee employee) throws Exception {
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
                                                        PASSWORD))))
                .andReturn();
    }

    private Person signedIn(TestIdentities.Organization org, String role) throws Exception {
        TestIdentities.Employee employee = create(org, role);
        MvcResult result = login(employee);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> body = body(result);
        return new Person(
                employee,
                (String) body.get("accessToken"),
                result.getResponse().getCookie("__Secure-peoplehub_rt").getValue(),
                (String) body.get("csrfToken"));
    }

    private MvcResult deactivate(Person caller, UUID target, String body) throws Exception {
        var request =
                post("/api/v1/admin/employees/" + target + "/deactivate")
                        .header("Authorization", "Bearer " + caller.accessToken());
        if (body != null) {
            request = request.contentType("application/json").content(body);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult deactivate(Person caller, UUID target) throws Exception {
        return deactivate(caller, target, null);
    }

    private MvcResult reactivate(Person caller, UUID target) throws Exception {
        return mvc.perform(
                        post("/api/v1/admin/employees/" + target + "/reactivate")
                                .header("Authorization", "Bearer " + caller.accessToken()))
                .andReturn();
    }

    private int meStatus(Person person) throws Exception {
        return mvc.perform(
                        get("/api/v1/me").header("Authorization", "Bearer " + person.accessToken()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int refreshStatus(Person person) throws Exception {
        return mvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(
                                        new Cookie("__Secure-peoplehub_rt", person.refreshToken()),
                                        new Cookie("__Secure-peoplehub_csrf", person.csrf()))
                                .header("X-CSRF-Token", person.csrf()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private Map<String, Object> employeeRow(UUID id) {
        return jdbc.queryForMap(
                "SELECT status, exit_date, password_hash, failed_login_count FROM employee"
                        + " WHERE id = ?",
                id);
    }

    private String status(UUID id) {
        return (String) employeeRow(id).get("status");
    }

    private List<Map<String, Object>> audits(UUID organizationId, String action) {
        return jdbc.queryForList(
                "SELECT actor_id, target_type, target_id, host(ip) AS ip,"
                        + " (details -> 'attributes')::text AS attributes"
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

    private LocalDate today(ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    // ---- deactivation ----

    @Test
    void anAdminDeactivatesAnEmployeeAndEveryWayInIsClosed() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person admin = signedIn(org, "ADMIN");
        Person employee = signedIn(org, "EMPLOYEE");
        MvcResult second = login(employee.employee());
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        jdbc.update("UPDATE employee SET failed_login_count = 2 WHERE id = ?", employee.id());
        Object passwordHash = employeeRow(employee.id()).get("password_hash");

        MvcResult result = deactivate(admin, employee.id());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(employeeRow(employee.id()))
                .containsEntry("status", "DEACTIVATED")
                .containsEntry("exit_date", null)
                .containsEntry("password_hash", passwordHash)
                .containsEntry("failed_login_count", 2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT revoke_reason FROM refresh_token"
                                        + " WHERE employee_id = ?",
                                String.class,
                                employee.id()))
                .containsExactly("DEACTIVATED");
        // Access token, refresh, and a new login all fail.
        assertThat(meStatus(employee)).isEqualTo(401);
        assertThat(refreshStatus(employee)).isEqualTo(401);
        assertThat(login(employee.employee()).getResponse().getStatus()).isEqualTo(401);
        assertThat(audits(org.id(), "REFRESH_TOKEN_REUSE_DETECTED")).isEmpty();
        assertThat(meStatus(admin)).isEqualTo(200);

        List<Map<String, Object>> audits = audits(org.id(), "EMPLOYEE_DEACTIVATED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .containsEntry("actor_id", admin.id().toString())
                .containsEntry("target_type", "EMPLOYEE")
                .containsEntry("target_id", employee.id().toString())
                .containsEntry("ip", "127.0.0.1");
        assertThat(JSON.readValue((String) audits.get(0).get("attributes"), Map.class))
                .containsOnlyKeys("role", "revokedSessions", "invitationRevoked")
                .containsEntry("role", "EMPLOYEE")
                .containsEntry("revokedSessions", 2)
                .containsEntry("invitationRevoked", false);
        assertThat((String) audits.get(0).get("attributes"))
                .doesNotContain(employee.employee().email());
    }

    @Test
    void whoMayDeactivateWhom() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person superAdmin = signedIn(org, "SUPER_ADMIN");
        Person admin = signedIn(org, "ADMIN");
        Person employee = signedIn(org, "EMPLOYEE");
        Person otherAdmin = signedIn(org, "ADMIN");
        Person otherSuperAdmin = signedIn(org, "SUPER_ADMIN");
        Person target = signedIn(org, "EMPLOYEE");

        Map<String, MvcResult> forbidden = new LinkedHashMap<>();
        forbidden.put("admin -> admin", deactivate(admin, otherAdmin.id()));
        forbidden.put("admin -> super admin", deactivate(admin, superAdmin.id()));
        forbidden.put("admin -> self", deactivate(admin, admin.id()));
        forbidden.put("super admin -> self", deactivate(superAdmin, superAdmin.id()));
        forbidden.put("employee -> employee", deactivate(employee, target.id()));
        forbidden.put("employee -> self", deactivate(employee, employee.id()));
        for (Map.Entry<String, MvcResult> attempt : forbidden.entrySet()) {
            assertThat(attempt.getValue().getResponse().getStatus())
                    .as(attempt.getKey())
                    .isEqualTo(403);
            assertThat(body(attempt.getValue()))
                    .as(attempt.getKey())
                    .containsEntry("type", "urn:peoplehub:problem:forbidden");
        }
        for (Person untouched : List.of(admin, otherAdmin, superAdmin, employee, target)) {
            assertThat(status(untouched.id())).isEqualTo("ACTIVE");
        }
        assertThat(audits(org.id(), "EMPLOYEE_DEACTIVATED")).isEmpty();

        assertThat(deactivate(admin, target.id()).getResponse().getStatus()).isEqualTo(204);
        assertThat(deactivate(superAdmin, otherAdmin.id()).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(deactivate(superAdmin, otherSuperAdmin.id()).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(deactivate(superAdmin, employee.id()).getResponse().getStatus()).isEqualTo(204);
        assertThat(audits(org.id(), "EMPLOYEE_DEACTIVATED")).hasSize(4);
    }

    @Test
    void theLastActiveSuperAdminCanNeverBeDeactivated() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person first = signedIn(org, "SUPER_ADMIN");
        Person second = signedIn(org, "SUPER_ADMIN");

        assertThat(deactivate(first, second.id()).getResponse().getStatus()).isEqualTo(204);
        // The one left cannot remove themselves, and the deactivated one can no longer act.
        assertThat(deactivate(first, first.id()).getResponse().getStatus()).isEqualTo(403);
        assertThat(deactivate(second, first.id()).getResponse().getStatus()).isEqualTo(401);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM employee WHERE organization_id = ?"
                                        + " AND role = 'SUPER_ADMIN' AND status = 'ACTIVE'",
                                Integer.class,
                                org.id()))
                .isEqualTo(1);
    }

    @Test
    void anInviteeIsDeactivatedWithTheirInvitationButInvitationsTheySentStay() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person superAdmin = signedIn(org, "SUPER_ADMIN");
        Person admin = signedIn(org, "ADMIN");
        String inviteeEmail = "invitee-" + UUID.randomUUID() + "@example.com";
        MvcResult invite =
                mvc.perform(
                                post("/api/v1/admin/employees/invite")
                                        .header("Authorization", "Bearer " + admin.accessToken())
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "Ivy Invitee",
                                                                "email",
                                                                inviteeEmail))))
                        .andReturn();
        assertThat(invite.getResponse().getStatus()).isEqualTo(201);
        UUID invitee = UUID.fromString((String) body(invite).get("employeeId"));
        String inviteCode =
                jdbc.queryForObject(
                        "SELECT payload -> 'attributes' ->> 'inviteCode' FROM email_outbox"
                                + " WHERE recipient = ?",
                        String.class,
                        inviteeEmail);

        // The admin who sent the invitation is deactivated: the invitation stays valid.
        assertThat(deactivate(superAdmin, admin.id()).getResponse().getStatus()).isEqualTo(204);
        assertThat(
                        mvc.perform(get("/api/v1/public/invitations/" + inviteCode + "/preview"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);

        // The invitee is deactivated: their own invitation is revoked.
        assertThat(deactivate(superAdmin, invitee).getResponse().getStatus()).isEqualTo(204);
        assertThat(status(invitee)).isEqualTo("DEACTIVATED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoked_at IS NOT NULL FROM employee_invitation"
                                        + " WHERE organization_id = ? AND email_normalized = ?",
                                Boolean.class,
                                org.id(),
                                inviteeEmail))
                .isTrue();
        assertThat(
                        mvc.perform(get("/api/v1/public/invitations/" + inviteCode + "/preview"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        assertThat(audits(org.id(), "EMPLOYEE_DEACTIVATED").get(1).get("attributes").toString())
                .contains("\"invitationRevoked\": true")
                .contains("\"revokedSessions\": 0");

        // Reactivated without a password, they are invited again, and can be sent a new invitation.
        assertThat(reactivate(superAdmin, invitee).getResponse().getStatus()).isEqualTo(204);
        assertThat(status(invitee)).isEqualTo("INVITED");
        MvcResult reinvite =
                mvc.perform(
                                post("/api/v1/admin/employees/invite")
                                        .header(
                                                "Authorization",
                                                "Bearer " + superAdmin.accessToken())
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "Ivy Invitee",
                                                                "email",
                                                                inviteeEmail))))
                        .andReturn();
        assertThat(reinvite.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(reinvite)).containsEntry("employeeId", invitee.toString());
    }

    @Test
    void onlyActiveAndInvitedEmployeesCanBeDeactivated() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person superAdmin = signedIn(org, "SUPER_ADMIN");
        TestIdentities.Employee deactivated = create(org, "EMPLOYEE");
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", deactivated.id());
        TestIdentities.Employee tombstone = create(org, "EMPLOYEE");
        jdbc.update(
                "UPDATE employee SET status = 'DELETED_TOMBSTONE' WHERE id = ?", tombstone.id());
        TestIdentities.Employee unverified = create(org, "SUPER_ADMIN");
        jdbc.update(
                "UPDATE employee SET status = 'PENDING_VERIFICATION' WHERE id = ?",
                unverified.id());

        for (UUID target : List.of(deactivated.id(), tombstone.id(), unverified.id())) {
            MvcResult result = deactivate(superAdmin, target);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(result)).containsEntry("type", "urn:peoplehub:problem:conflict");
        }
        assertThat(status(tombstone.id())).isEqualTo("DELETED_TOMBSTONE");
        assertThat(status(unverified.id())).isEqualTo("PENDING_VERIFICATION");
        assertThat(audits(org.id(), "EMPLOYEE_DEACTIVATED")).isEmpty();
    }

    // ---- exit date ----

    @Test
    void theExitDateIsStoredOnlyWhenGivenAndNeverInTheFuture() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person admin = signedIn(org, "ADMIN");
        TestIdentities.Employee today = create(org, "EMPLOYEE");
        TestIdentities.Employee past = create(org, "EMPLOYEE");
        TestIdentities.Employee future = create(org, "EMPLOYEE");
        TestIdentities.Employee explicitNull = create(org, "EMPLOYEE");
        LocalDate todayInOrg = today(ZoneId.of("Asia/Kolkata"));

        assertThat(
                        deactivate(admin, today.id(), "{\"exitDate\":\"" + todayInOrg + "\"}")
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        assertThat(
                        deactivate(admin, past.id(), "{\"exitDate\":\"2026-01-31\"}")
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        assertThat(
                        deactivate(admin, explicitNull.id(), "{\"exitDate\":null}")
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        MvcResult tomorrow =
                deactivate(admin, future.id(), "{\"exitDate\":\"" + todayInOrg.plusDays(1) + "\"}");

        assertThat(employeeRow(today.id())).containsEntry("exit_date", Date.valueOf(todayInOrg));
        assertThat(employeeRow(past.id()))
                .containsEntry("exit_date", Date.valueOf(LocalDate.parse("2026-01-31")));
        assertThat(employeeRow(explicitNull.id())).containsEntry("exit_date", null);
        assertThat(tomorrow.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(tomorrow))
                .containsEntry("type", "urn:peoplehub:problem:validation-error")
                .containsEntry(
                        "fieldErrors",
                        List.of(
                                Map.of(
                                        "field",
                                        "exitDate",
                                        "message",
                                        "must not be in the future")));
        assertThat(status(future.id())).isEqualTo("ACTIVE");
        assertThat(
                        deactivate(admin, future.id(), "{\"exitDate\":\"not-a-date\"}")
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }

    @Test
    void theExitDateIsJudgedInTheOrganizationsOwnTimezone() throws Exception {
        // Kiritimati is UTC+14 and Pago Pago UTC-11: 25 hours apart, so "today" in Kiritimati is
        // always a future date in Pago Pago.
        TestIdentities.Organization ahead = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Organization behind = TestIdentities.activeOrganization(jdbc);
        jdbc.update(
                "UPDATE organization SET timezone = 'Pacific/Kiritimati' WHERE id = ?", ahead.id());
        jdbc.update(
                "UPDATE organization SET timezone = 'Pacific/Pago_Pago' WHERE id = ?", behind.id());
        Person aheadAdmin = signedIn(ahead, "ADMIN");
        Person behindAdmin = signedIn(behind, "ADMIN");
        TestIdentities.Employee aheadTarget = create(ahead, "EMPLOYEE");
        TestIdentities.Employee behindTarget = create(behind, "EMPLOYEE");
        String body = "{\"exitDate\":\"" + today(ZoneId.of("Pacific/Kiritimati")) + "\"}";

        assertThat(deactivate(aheadAdmin, aheadTarget.id(), body).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(deactivate(behindAdmin, behindTarget.id(), body).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void checksRunInTheOrder404Then403Then409Then400() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person admin = signedIn(org, "ADMIN");
        Person otherAdmin = signedIn(org, "ADMIN");
        TestIdentities.Employee deactivated = create(org, "EMPLOYEE");
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", deactivated.id());
        TestIdentities.Employee foreign = create(TestIdentities.activeOrganization(jdbc), "ADMIN");
        String future = "{\"exitDate\":\"" + today(ZoneId.of("Asia/Kolkata")).plusDays(5) + "\"}";

        assertThat(deactivate(admin, foreign.id(), future).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(deactivate(admin, otherAdmin.id(), future).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(deactivate(admin, deactivated.id(), future).getResponse().getStatus())
                .isEqualTo(409);
    }

    // ---- tenant boundary ----

    @Test
    void anotherOrganizationsEmployeeIsTheSame404AsAnUnknownId() throws Exception {
        Person superAdmin = signedIn(TestIdentities.activeOrganization(jdbc), "SUPER_ADMIN");
        TestIdentities.Organization otherOrg = TestIdentities.activeOrganization(jdbc);
        Person foreign = signedIn(otherOrg, "EMPLOYEE");

        MvcResult unknown = deactivate(superAdmin, UUID.randomUUID());
        MvcResult crossDeactivate = deactivate(superAdmin, foreign.id());
        MvcResult crossReactivate = reactivate(superAdmin, foreign.id());

        for (MvcResult result : List.of(unknown, crossDeactivate, crossReactivate)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(comparable(result)).isEqualTo(comparable(unknown));
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(foreign.id().toString());
        }
        assertThat(body(unknown)).containsEntry("detail", EmployeeLifecycleService.NOT_FOUND);
        assertThat(status(foreign.id())).isEqualTo("ACTIVE");
        assertThat(meStatus(foreign)).isEqualTo(200);
        assertThat(audits(otherOrg.id(), "EMPLOYEE_DEACTIVATED")).isEmpty();
    }

    // ---- password reset ----

    @Test
    void deactivationInvalidatesResetCodesAndForgotPasswordSendsNothing() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person admin = signedIn(org, "ADMIN");
        Person employee = signedIn(org, "EMPLOYEE");
        String forgot =
                JSON.writeValueAsString(
                        Map.of(
                                "organization",
                                employee.employee().organizationLoginKey(),
                                "email",
                                employee.employee().email()));
        mvc.perform(
                        post("/api/v1/auth/forgot-password")
                                .contentType("application/json")
                                .content(forgot))
                .andReturn();
        String code =
                jdbc.queryForObject(
                        "SELECT payload -> 'attributes' ->> 'resetCode' FROM email_outbox"
                                + " WHERE recipient = ? AND type = 'PASSWORD_RESET'",
                        String.class,
                        employee.employee().email());

        deactivate(admin, employee.id());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT invalidated_at IS NOT NULL FROM password_reset_token"
                                        + " WHERE employee_id = ?",
                                Boolean.class,
                                employee.id()))
                .isTrue();
        String reset =
                JSON.writeValueAsString(
                        Map.of(
                                "token",
                                code,
                                "password",
                                "Vq7#nR3tLm9@pZx2Kd",
                                "confirmPassword",
                                "Vq7#nR3tLm9@pZx2Kd"));
        assertThat(
                        mvc.perform(
                                        post("/api/v1/auth/reset-password")
                                                .contentType("application/json")
                                                .content(reset))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        jdbc.update(
                "UPDATE password_reset_token SET created_at = created_at - interval '1 day'"
                        + " WHERE employee_id = ?",
                employee.id());
        mvc.perform(
                        post("/api/v1/auth/forgot-password")
                                .contentType("application/json")
                                .content(forgot))
                .andReturn();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM password_reset_token WHERE employee_id = ?",
                                Integer.class,
                                employee.id()))
                .isEqualTo(1);

        // The code stays dead after reactivation too.
        reactivate(admin, employee.id());
        assertThat(
                        mvc.perform(
                                        post("/api/v1/auth/reset-password")
                                                .contentType("application/json")
                                                .content(reset))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }

    // ---- reactivation ----

    @Test
    void reactivationRestoresAccessButTheyMustSignInAgain() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person superAdmin = signedIn(org, "SUPER_ADMIN");
        Person admin = signedIn(org, "ADMIN");
        jdbc.update("UPDATE employee SET failed_login_count = 3 WHERE id = ?", admin.id());
        deactivate(
                superAdmin,
                admin.id(),
                "{\"exitDate\":\"" + today(ZoneId.of("Asia/Kolkata")) + "\"}");

        MvcResult result = reactivate(superAdmin, admin.id());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(employeeRow(admin.id()))
                .containsEntry("status", "ACTIVE")
                .containsEntry("exit_date", null)
                .containsEntry("failed_login_count", 3);
        // No session comes back.
        assertThat(meStatus(admin)).isEqualTo(401);
        assertThat(refreshStatus(admin)).isEqualTo(401);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refresh_token WHERE employee_id = ?"
                                        + " AND NOT revoked",
                                Integer.class,
                                admin.id()))
                .isZero();
        assertThat(login(admin.employee()).getResponse().getStatus()).isEqualTo(200);

        List<Map<String, Object>> audits = audits(org.id(), "EMPLOYEE_REACTIVATED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .containsEntry("actor_id", superAdmin.id().toString())
                .containsEntry("target_id", admin.id().toString());
        assertThat(JSON.readValue((String) audits.get(0).get("attributes"), Map.class))
                .containsOnlyKeys("role", "status")
                .containsEntry("role", "ADMIN")
                .containsEntry("status", "ACTIVE");
    }

    @Test
    void reactivationFollowsTheSameRulesAndOnlyAppliesToTheDeactivated() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person superAdmin = signedIn(org, "SUPER_ADMIN");
        Person admin = signedIn(org, "ADMIN");
        Person otherAdmin = signedIn(org, "ADMIN");
        Person active = signedIn(org, "EMPLOYEE");
        deactivate(superAdmin, otherAdmin.id());

        MvcResult notDeactivated = reactivate(superAdmin, active.id());
        assertThat(notDeactivated.getResponse().getStatus()).isEqualTo(409);
        assertThat(reactivate(admin, otherAdmin.id()).getResponse().getStatus()).isEqualTo(403);
        assertThat(status(otherAdmin.id())).isEqualTo("DEACTIVATED");
        assertThat(audits(org.id(), "EMPLOYEE_REACTIVATED")).isEmpty();
    }

    // ---- history ----

    @Test
    void deactivationDeletesNothing() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        Person admin = signedIn(org, "ADMIN");
        Person employee = signedIn(org, "EMPLOYEE");
        int tokens =
                jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token WHERE employee_id = ?",
                        Integer.class,
                        employee.id());
        int auditRows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE organization_id = ?",
                        Integer.class,
                        org.id());
        int attempts =
                jdbc.queryForObject(
                        "SELECT count(*) FROM login_attempt WHERE email_attempted = ?",
                        Integer.class,
                        employee.employee().email());

        deactivate(admin, employee.id());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM employee WHERE id = ?",
                                Integer.class,
                                employee.id()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refresh_token WHERE employee_id = ?",
                                Integer.class,
                                employee.id()))
                .isEqualTo(tokens);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_log WHERE organization_id = ?",
                                Integer.class,
                                org.id()))
                .isEqualTo(auditRows + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM login_attempt WHERE email_attempted = ?",
                                Integer.class,
                                employee.employee().email()))
                .isEqualTo(attempts);
    }

    @Test
    void bothEndpointsNeedAnAccessToken() throws Exception {
        for (String action : List.of("deactivate", "reactivate")) {
            MvcResult result =
                    mvc.perform(post("/api/v1/admin/employees/" + UUID.randomUUID() + "/" + action))
                            .andReturn();
            assertThat(result.getResponse().getStatus()).as(action).isEqualTo(401);
        }
    }
}
