package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.peoplehub.support.IndistinguishableResponses;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adversarial cross-tenant checks of every B2 endpoint that takes an id (b2-8 C5; Spec D22, 2.1.2,
 * 15.1): a caller of organization A presents organization B's real id, and the answer must be
 * exactly what an id nobody has gets (the same status, body and headers, so nothing reveals that B
 * or its resource exists), with nothing changed in either organization, audit included.
 *
 * <p>Organization B is fully populated (an active and a deactivated employee, an open invitation, a
 * live session) and every attempt comes from a real signed-in Admin or Super Admin of A, the
 * strongest callers the endpoints have. The application runs as the runtime role under row-level
 * security; the privileged fixture only builds the two organizations and reads their state.
 */
@IntegrationTest
@AutoConfigureMockMvc
class CrossTenantApiIsolationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Tenant-pass " + UUID.randomUUID();

    /** Every tenant-owned table and its tenant key (V25). */
    private static final Map<String, String> TENANT_TABLES = new LinkedHashMap<>();

    static {
        TENANT_TABLES.put("organization", "id");
        for (String table :
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
                        "notification_preference")) {
            TENANT_TABLES.put(table, "organization_id");
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;

    private World world;

    /** Organization A's callers, and organization B's resources. */
    private record World(
            UUID orgA,
            UUID orgB,
            String superAdminA,
            String adminA,
            UUID employeeB,
            UUID deactivatedB,
            UUID invitationB,
            UUID sessionB) {}

    @BeforeEach
    void buildTwoOrganizations() throws Exception {
        TestIdentities.Organization a = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Organization b = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee superAdminA = employee(a, "SUPER_ADMIN");
        TestIdentities.Employee adminA = employee(a, "ADMIN");

        TestIdentities.Employee superAdminB = employee(b, "SUPER_ADMIN");
        TestIdentities.Employee employeeB = employee(b, "EMPLOYEE");
        TestIdentities.Employee deactivatedB = employee(b, "EMPLOYEE");
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", deactivatedB.id());
        String inviteeEmail = "invitee-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, status, role, join_date) VALUES (?, ?, 'Ivy"
                        + " Invitee', ?, ?, 'INVITED', 'EMPLOYEE', DATE '2026-01-05')",
                b.id(),
                "E-" + UUID.randomUUID(),
                inviteeEmail,
                inviteeEmail);
        UUID invitationB =
                jdbc.queryForObject(
                        "INSERT INTO employee_invitation (organization_id, email_normalized,"
                                + " intended_role, token_hash, inviter_employee_id, expires_at)"
                                + " VALUES (?, ?, 'EMPLOYEE', ?, ?, now() + interval '7 days')"
                                + " RETURNING id",
                        UUID.class,
                        b.id(),
                        inviteeEmail,
                        "hash-" + UUID.randomUUID(),
                        superAdminB.id());
        UUID sessionB = TestIdentities.activeSession(jdbc, employeeB, Instant.now());

        world =
                new World(
                        a.id(),
                        b.id(),
                        bearer(superAdminA),
                        bearer(adminA),
                        employeeB.id(),
                        deactivatedB.id(),
                        invitationB,
                        sessionB);
    }

    // ---- the matrix ----

    /** One id-taking endpoint: how to call it with an id, and which of B's ids to try. */
    private record Endpoint(
            String name,
            Function<UUID, MockHttpServletRequestBuilder> request,
            Function<World, UUID> foreignId) {}

    private static final List<Endpoint> ENDPOINTS =
            List.of(
                    new Endpoint(
                            "deactivate",
                            id -> json(post("/api/v1/admin/employees/" + id + "/deactivate"), "{}"),
                            World::employeeB),
                    new Endpoint(
                            "reactivate",
                            id -> post("/api/v1/admin/employees/" + id + "/reactivate"),
                            World::deactivatedB),
                    new Endpoint(
                            "promote-admin",
                            id -> post("/api/v1/super-admin/employees/" + id + "/promote-admin"),
                            World::employeeB),
                    new Endpoint(
                            "mfa-required",
                            id ->
                                    json(
                                            put(
                                                    "/api/v1/super-admin/employees/"
                                                            + id
                                                            + "/mfa-required"),
                                            "{\"required\":true}"),
                            World::employeeB),
                    new Endpoint(
                            "mfa-reset",
                            id -> post("/api/v1/admin/employees/" + id + "/mfa/reset"),
                            World::employeeB),
                    new Endpoint(
                            "invitation-resend",
                            id -> post("/api/v1/admin/invitations/" + id + "/resend"),
                            World::invitationB),
                    new Endpoint(
                            "invitation-revoke",
                            id -> post("/api/v1/admin/invitations/" + id + "/revoke"),
                            World::invitationB),
                    new Endpoint(
                            "session-revoke",
                            id -> delete("/api/v1/me/sessions/" + id),
                            World::sessionB));

    static Stream<Arguments> endpointsAndCallers() {
        return ENDPOINTS.stream()
                .flatMap(
                        endpoint ->
                                Stream.of("SUPER_ADMIN", "ADMIN")
                                        .map(caller -> Arguments.of(endpoint.name(), caller)));
    }

    @ParameterizedTest(name = "{1} calling {0} with another organization''s id")
    @MethodSource("endpointsAndCallers")
    void anotherOrganizationsIdIsIndistinguishableFromAnUnknownOneAndChangesNothing(
            String endpointName, String caller) throws Exception {
        Endpoint endpoint =
                ENDPOINTS.stream().filter(e -> e.name().equals(endpointName)).findFirst().get();
        String bearer = caller.equals("SUPER_ADMIN") ? world.superAdminA() : world.adminA();
        Map<String, String> before = fingerprints();

        MvcResult foreign =
                call(endpoint.request().apply(endpoint.foreignId().apply(world)), bearer);
        MvcResult unknown = call(endpoint.request().apply(UUID.randomUUID()), bearer);

        // Not found, or refused by role before any lookup: never a hint about B.
        assertThat(unknown.getResponse().getStatus()).as(endpointName).isIn(403, 404);
        IndistinguishableResponses.assertIndistinguishable(unknown, foreign, endpointName);
        assertThat(fingerprints())
                .as("neither organization changed, audit included")
                .isEqualTo(before);
    }

    // ---- lists and the caller's own view ----

    @Test
    void theSessionsListShowsOnlyTheCallersOwnSessionsEvenWithTheSameEmailElsewhere()
            throws Exception {
        TestIdentities.Organization a = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Organization b = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee inA = employee(a, "EMPLOYEE");
        TestIdentities.Employee inB = employee(b, "EMPLOYEE");
        // The same email in B, with two live sessions there.
        jdbc.update(
                "UPDATE employee SET email = ?, email_normalized = ? WHERE id = ?",
                inA.email(),
                inA.email(),
                inB.id());
        TestIdentities.activeSession(jdbc, inB, Instant.now());
        TestIdentities.activeSession(jdbc, inB, Instant.now());
        String bearerA = bearer(inA);

        Map<String, Object> page = body(call(get("/api/v1/me/sessions"), bearerA));

        assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(1);
        List<String> bSessions =
                jdbc.queryForList(
                        "SELECT DISTINCT family_id::text FROM refresh_token WHERE organization_id = ?",
                        String.class,
                        b.id());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items)
                .extracting(item -> item.get("sessionId"))
                .doesNotContainAnyElementsOf(bSessions);
        Map<String, Object> me = body(call(get("/api/v1/me"), bearerA));
        assertThat(me.get("id")).isEqualTo(inA.id().toString());
    }

    @Test
    void invitingAnEmailThatExistsOnlyInAnotherOrganizationIsNotAConflict() throws Exception {
        String emailOfB =
                jdbc.queryForObject(
                        "SELECT email FROM employee WHERE id = ?", String.class, world.employeeB());
        Map<String, String> before = fingerprints();

        MvcResult invited =
                call(
                        json(
                                post("/api/v1/admin/employees/invite"),
                                JSON.writeValueAsString(
                                        Map.of("name", "Same Email", "email", emailOfB))),
                        world.superAdminA());

        // A new invitation in A: B's account neither blocks it nor shows through it.
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        assertThat(fingerprints().get(fingerprintKey(world.orgB())))
                .as("B is untouched")
                .isEqualTo(before.get(fingerprintKey(world.orgB())));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM employee WHERE email_normalized = ?"
                                        + " AND organization_id = ?",
                                Long.class,
                                emailOfB.toLowerCase(),
                                world.orgA()))
                .isEqualTo(1);
    }

    @Test
    void aSuccessfulActionIsAuditedInTheCallersOrganizationOnly() throws Exception {
        TestIdentities.Organization a =
                new TestIdentities.Organization(
                        world.orgA(),
                        jdbc.queryForObject(
                                "SELECT login_key_normalized FROM organization WHERE id = ?",
                                String.class,
                                world.orgA()));
        TestIdentities.Employee ownEmployee = employee(a, "EMPLOYEE");
        Map<String, String> before = fingerprints();
        long auditInABefore = auditRows(world.orgA());

        MvcResult result =
                call(
                        json(
                                post("/api/v1/admin/employees/" + ownEmployee.id() + "/deactivate"),
                                "{}"),
                        world.superAdminA());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(auditRows(world.orgA())).isEqualTo(auditInABefore + 1);
        Map<String, Object> audit =
                jdbc.queryForMap(
                        "SELECT organization_id, actor_id, target_id FROM audit_log"
                                + " WHERE organization_id = ? AND action = 'EMPLOYEE_DEACTIVATED'"
                                + " ORDER BY id DESC LIMIT 1",
                        world.orgA());
        assertThat(audit.get("organization_id")).isEqualTo(world.orgA());
        assertThat(audit.get("target_id")).isEqualTo(ownEmployee.id().toString());
        assertThat(fingerprints().get(fingerprintKey(world.orgB())))
                .as("B is untouched")
                .isEqualTo(before.get(fingerprintKey(world.orgB())));
    }

    // ---- helpers ----

    private TestIdentities.Employee employee(TestIdentities.Organization org, String role) {
        return TestIdentities.activeEmployee(jdbc, org, role, passwordHasher.hash(PASSWORD));
    }

    private String bearer(TestIdentities.Employee employee) throws Exception {
        MvcResult login =
                mvc.perform(
                                json(
                                        post("/api/v1/auth/login"),
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "organization",
                                                                employee.organizationLoginKey(),
                                                        "email", employee.email(),
                                                        "password", PASSWORD))))
                        .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return "Bearer " + body(login).get("accessToken");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mvc.perform(request.header("Authorization", bearer)).andReturn();
    }

    private static MockHttpServletRequestBuilder json(
            MockHttpServletRequestBuilder request, String body) {
        return request.contentType("application/json").content(body);
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        String text = result.getResponse().getContentAsString();
        return text.isEmpty() ? Map.of() : JSON.readValue(text, new TypeReference<>() {});
    }

    private long auditRows(UUID org) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ?", Long.class, org);
    }

    private String fingerprintKey(UUID org) {
        return org.toString();
    }

    /** A digest of every row both organizations own, per organization. */
    private Map<String, String> fingerprints() {
        Map<String, String> fingerprints = new TreeMap<>();
        for (UUID org : List.of(world.orgA(), world.orgB())) {
            StringBuilder digest = new StringBuilder();
            for (Map.Entry<String, String> table : TENANT_TABLES.entrySet()) {
                digest.append(table.getKey())
                        .append('=')
                        .append(
                                jdbc.queryForObject(
                                        "SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '')"
                                                + " FROM "
                                                + table.getKey()
                                                + " t WHERE "
                                                + table.getValue()
                                                + " = ?",
                                        String.class,
                                        org))
                        .append(';');
            }
            fingerprints.put(fingerprintKey(org), digest.toString());
        }
        return fingerprints;
    }
}
