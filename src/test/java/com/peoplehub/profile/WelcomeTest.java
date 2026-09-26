package com.peoplehub.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * The one-time welcome (b2-4, B2-4/O12; Spec 10.2, 10.3, D17) end to end: invitation, private
 * password, sign-in, welcome, dashboard. Also checks the OpenAPI document describes the b2-4
 * endpoints' authentication correctly. Runs on a controllable clock so the recorded time is exact.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
class WelcomeTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String ACK = "/api/v1/me/welcome/ack";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;
    @Autowired private MutableClock clock;

    private TestIdentities.Organization org;
    private TestIdentities.Employee admin;

    @BeforeEach
    void anOrganizationWithAnAdmin() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        org = TestIdentities.activeOrganization(jdbc);
        admin = TestIdentities.activeEmployee(jdbc, org, "ADMIN", null);
    }

    private String tokenFor(TestIdentities.Employee caller) {
        UUID session = TestIdentities.activeSession(jdbc, caller, clock.instant());
        return issuer.issue(caller.id(), caller.organizationId(), caller.role(), session).value();
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private MvcResult me(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                .andReturn();
    }

    private MvcResult acknowledge(String accessToken) throws Exception {
        return mvc.perform(post(ACK).header("Authorization", "Bearer " + accessToken)).andReturn();
    }

    /** Invites, accepts and signs in through the real endpoints; returns the access token. */
    private String invitedEmployeeSignsIn(String name) throws Exception {
        String email = "invitee-" + UUID.randomUUID() + "@example.com";
        assertThat(
                        mvc.perform(
                                        post("/api/v1/admin/employees/invite")
                                                .header(
                                                        "Authorization",
                                                        "Bearer " + tokenFor(admin))
                                                .contentType("application/json")
                                                .content(
                                                        JSON.writeValueAsString(
                                                                Map.of(
                                                                        "name", name, "email",
                                                                        email))))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);
        String inviteCode =
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
        assertThat(
                        mvc.perform(
                                        post("/api/v1/public/invitations/" + inviteCode + "/accept")
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
                                .getStatus())
                .isEqualTo(204);
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
                                                                PASSWORD))))
                        .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return (String) body(login).get("accessToken");
    }

    @Test
    void aNewlyActivatedEmployeeIsWelcomedOnceAndThenNeverAgain() throws Exception {
        String accessToken = invitedEmployeeSignsIn("Riya Sharma");

        Map<String, Object> first = body(me(accessToken));
        assertThat(first)
                .containsEntry("firstName", "Riya")
                .containsEntry("status", "ACTIVE")
                .containsEntry("welcomeSeenAt", null);

        clock.advance(Duration.ofMinutes(2));
        Instant acknowledgedAt = clock.instant();
        MvcResult ack = acknowledge(accessToken);
        assertThat(ack.getResponse().getStatus()).isEqualTo(204);
        assertThat(ack.getResponse().getContentAsString()).isEmpty();
        assertThat(body(me(accessToken))).containsEntry("welcomeSeenAt", acknowledgedAt.toString());

        // Acknowledging again changes nothing.
        clock.advance(Duration.ofMinutes(5)); // still inside the 15-minute access token
        assertThat(acknowledge(accessToken).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(me(accessToken))).containsEntry("welcomeSeenAt", acknowledgedAt.toString());
    }

    @Test
    void acknowledgingChangesOnlyTheCallersOwnRow() throws Exception {
        TestIdentities.Employee colleague =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee elsewhere =
                TestIdentities.activeEmployee(jdbc, other, "EMPLOYEE", null);

        assertThat(acknowledge(tokenFor(admin)).getResponse().getStatus()).isEqualTo(204);

        assertThat(welcomeSeenAt(admin.id())).isNotNull();
        assertThat(welcomeSeenAt(colleague.id())).isNull();
        assertThat(welcomeSeenAt(elsewhere.id())).isNull();
    }

    @Test
    void acknowledgingNeedsAnAccessToken() throws Exception {
        MvcResult result = mvc.perform(post(ACK)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void theOpenApiDocumentDescribesTheB24EndpointsAuthentication() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/me/welcome/ack'].post.security[0]",
                                hasKey("bearerAuth")))
                .andExpect(
                        jsonPath("$.paths['/api/v1/me/welcome/ack'].post.responses['401']")
                                .exists())
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/admin/employees/invite'].post.security[0]",
                                hasKey("bearerAuth")))
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/super-admin/admins/invite'].post.security[0]",
                                hasKey("bearerAuth")))
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/admin/invitations/{id}/resend'].post.security[0]",
                                hasKey("bearerAuth")))
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/admin/invitations/{id}/revoke'].post.responses['403']")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/public/invitations/{token}/preview'].get.security")
                                .doesNotExist())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/public/invitations/{token}/accept'].post.security")
                                .doesNotExist())
                .andExpect(
                        jsonPath("$.components.schemas.MeResponse.properties.firstName").exists())
                .andExpect(
                        jsonPath("$.components.schemas.MeResponse.properties.welcomeSeenAt")
                                .exists());
    }

    private Object welcomeSeenAt(UUID employeeId) {
        return jdbc.queryForObject(
                "SELECT welcome_seen_at FROM employee WHERE id = ?", Object.class, employeeId);
    }
}
