package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestIdentities;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * One password policy wherever a password is set (b2-5, B2-5/P1, P2): registration and invitation
 * acceptance both reject a breached password and one longer than 128 characters, with a field error
 * on {@code password} that never echoes it, and nothing is created or activated.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PasswordPolicyIntegrationTest {

    private static final String BREACHED = "01TeleMike01";
    private static final String TOO_LONG = "xk9$mQ2vTz8!wLp4".repeat(8) + "x";
    private static final String GOOD = "xk9$mQ2vTz8!wLp4Rb";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;

    private MvcResult register(String organizationName, String email, String password)
            throws Exception {
        return mvc.perform(
                        post("/api/v1/public/organizations/register")
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "organizationName",
                                                        organizationName,
                                                        "timezone",
                                                        "Asia/Kolkata",
                                                        "founderFullName",
                                                        "Jane Founder",
                                                        "companyEmail",
                                                        email,
                                                        "password",
                                                        password,
                                                        "confirmPassword",
                                                        password,
                                                        "termsAccepted",
                                                        true))))
                .andReturn();
    }

    private static void assertPasswordRejected(MvcResult result, String password) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .contains("urn:peoplehub:problem:validation-error")
                .contains("\"field\":\"password\"")
                .doesNotContain(password);
    }

    @Test
    void registrationRejectsABreachedOrOverlongPasswordAndCreatesNothing() throws Exception {
        for (String password : new String[] {BREACHED, TOO_LONG}) {
            String organizationName = "Policy Org " + UUID.randomUUID();

            assertPasswordRejected(
                    register(
                            organizationName,
                            "founder-" + UUID.randomUUID() + "@example.com",
                            password),
                    password);

            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM organization WHERE name = ?",
                                    Integer.class,
                                    organizationName))
                    .isZero();
        }
        // The same request with a good password goes through.
        assertThat(
                        register(
                                        "Policy Org " + UUID.randomUUID(),
                                        "founder-" + UUID.randomUUID() + "@example.com",
                                        GOOD)
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);
    }

    @Test
    void invitationAcceptanceRejectsABreachedOrOverlongPasswordAndActivatesNothing()
            throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee admin = TestIdentities.activeEmployee(jdbc, org, "ADMIN", null);
        String accessToken =
                issuer.issue(
                                admin.id(),
                                org.id(),
                                "ADMIN",
                                TestIdentities.activeSession(jdbc, admin, Instant.now()))
                        .value();
        String email = "invitee-" + UUID.randomUUID() + "@example.com";
        assertThat(
                        mvc.perform(
                                        post("/api/v1/admin/employees/invite")
                                                .header("Authorization", "Bearer " + accessToken)
                                                .contentType("application/json")
                                                .content(
                                                        JSON.writeValueAsString(
                                                                Map.of(
                                                                        "name", "Riya", "email",
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

        for (String password : new String[] {BREACHED, TOO_LONG}) {
            MvcResult result =
                    mvc.perform(
                                    post("/api/v1/public/invitations/" + inviteCode + "/accept")
                                            .contentType("application/json")
                                            .content(
                                                    JSON.writeValueAsString(
                                                            Map.of(
                                                                    "password",
                                                                    password,
                                                                    "confirmPassword",
                                                                    password))))
                            .andReturn();
            assertPasswordRejected(result, password);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM employee WHERE organization_id = ?"
                                        + " AND email_normalized = ?",
                                String.class,
                                org.id(),
                                email))
                .isEqualTo("INVITED");
    }
}
