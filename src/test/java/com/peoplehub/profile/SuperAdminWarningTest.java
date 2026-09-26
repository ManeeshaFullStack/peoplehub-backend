package com.peoplehub.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestIdentities;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /me} {@code needsAdditionalSuperAdmin} (b2-7, B2-7/13; Spec 3.3): true only for a
 * Super Admin whose own organization has fewer than two active Super Admins; informational, never
 * blocking, and the count itself is never exposed.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SuperAdminWarningTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;

    @Test
    void theOnlyActiveSuperAdminIsWarned() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee founder = employee(org, "SUPER_ADMIN");

        assertThat(me(founder)).containsEntry("needsAdditionalSuperAdmin", true);
    }

    @Test
    void twoActiveSuperAdminsAreNotWarned() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee founder = employee(org, "SUPER_ADMIN");
        TestIdentities.Employee second = employee(org, "SUPER_ADMIN");

        assertThat(me(founder)).containsEntry("needsAdditionalSuperAdmin", false);
        assertThat(me(second)).containsEntry("needsAdditionalSuperAdmin", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEACTIVATED", "INVITED"})
    void aSuperAdminWhoIsNotActiveDoesNotCountAsTheSecond(String status) throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee founder = employee(org, "SUPER_ADMIN");
        TestIdentities.Employee second = employee(org, "SUPER_ADMIN");
        jdbc.update("UPDATE employee SET status = ? WHERE id = ?", status, second.id());

        assertThat(me(founder)).containsEntry("needsAdditionalSuperAdmin", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "EMPLOYEE"})
    void adminsAndEmployeesAreNeverWarned(String role) throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        employee(org, "SUPER_ADMIN");
        TestIdentities.Employee caller = employee(org, role);

        assertThat(me(caller)).containsEntry("needsAdditionalSuperAdmin", false);
    }

    @Test
    void anotherOrganizationsSuperAdminsNeverCount() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee founder = employee(org, "SUPER_ADMIN");
        employee(other, "SUPER_ADMIN");
        employee(other, "SUPER_ADMIN");

        assertThat(me(founder)).containsEntry("needsAdditionalSuperAdmin", true);
    }

    @Test
    void theWarningBlocksNothingAndExposesNoCount() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee founder = employee(org, "SUPER_ADMIN");
        String bearer = bearer(founder);

        MvcResult me = mvc.perform(get("/api/v1/me").header("Authorization", bearer)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(me.getResponse().getContentAsString())
                .doesNotContainIgnoringCase("count")
                .doesNotContainIgnoringCase("superAdmins");
        // Onboarding completes while the warning stands.
        assertThat(
                        mvc.perform(
                                        post("/api/v1/organization/onboarding/complete")
                                                .header("Authorization", bearer))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        assertThat(me(founder)).containsEntry("needsAdditionalSuperAdmin", true);
    }

    @Test
    void theOpenApiDocumentDescribesTheFlag() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.MeResponse.properties.needsAdditionalSuperAdmin.type")
                                .value("boolean"));
    }

    private TestIdentities.Employee employee(TestIdentities.Organization org, String role) {
        return TestIdentities.activeEmployee(jdbc, org, role, null);
    }

    private String bearer(TestIdentities.Employee caller) {
        UUID session = TestIdentities.activeSession(jdbc, caller, Instant.now());
        return "Bearer "
                + issuer.issue(caller.id(), caller.organizationId(), caller.role(), session)
                        .value();
    }

    private Map<String, Object> me(TestIdentities.Employee caller) throws Exception {
        MvcResult result =
                mvc.perform(get("/api/v1/me").header("Authorization", bearer(caller))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }
}
