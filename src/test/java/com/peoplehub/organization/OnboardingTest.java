package com.peoplehub.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.TestIdentities;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code POST /api/v1/organization/onboarding/complete} (b2-7, B2-7/21; Spec 2.1.3 step 8, 13.0):
 * Super Admin only, recorded once at server time, audited once, the caller's own organization only.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
class OnboardingTest {

    private static final String COMPLETE = "/api/v1/organization/onboarding/complete";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;
    @Autowired private MutableClock clock;

    @BeforeEach
    void now() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void aSuperAdminCompletesOnboardingOnceAtServerTime() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee founder =
                TestIdentities.activeEmployee(jdbc, org, "SUPER_ADMIN", null);
        Instant completedAt = clock.instant();

        MvcResult result = complete(founder);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(completedAt(org)).isEqualTo(completedAt);
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(founder)))
                .andExpect(jsonPath("$.organization.onboardingCompleted").value(true));
        assertThat(audits(org)).isEqualTo(1);

        // Again, later: nothing changes and no second audit row.
        clock.advance(Duration.ofMinutes(3));
        assertThat(complete(founder).getResponse().getStatus()).isEqualTo(204);
        assertThat(completedAt(org)).isEqualTo(completedAt);
        assertThat(audits(org)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "EMPLOYEE"})
    void onlyASuperAdminMayCompleteOnboarding(String role) throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee caller = TestIdentities.activeEmployee(jdbc, org, role, null);

        MvcResult result = complete(caller);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(completedAt(org)).isNull();
        assertThat(audits(org)).isZero();
    }

    @Test
    void completingChangesOnlyTheCallersOwnOrganization() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee founder =
                TestIdentities.activeEmployee(jdbc, org, "SUPER_ADMIN", null);

        assertThat(complete(founder).getResponse().getStatus()).isEqualTo(204);

        assertThat(completedAt(other)).isNull();
        assertThat(audits(other)).isZero();
    }

    @Test
    void completingNeedsAnAccessToken() throws Exception {
        assertThat(mvc.perform(post(COMPLETE)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void theOpenApiDocumentDescribesTheEndpoint() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(
                        jsonPath(
                                "$.paths['" + COMPLETE + "'].post.security[0]",
                                hasKey("bearerAuth")))
                .andExpect(jsonPath("$.paths['" + COMPLETE + "'].post.responses['403']").exists());
    }

    private MvcResult complete(TestIdentities.Employee caller) throws Exception {
        return mvc.perform(post(COMPLETE).header("Authorization", bearer(caller))).andReturn();
    }

    private String bearer(TestIdentities.Employee caller) {
        UUID session = TestIdentities.activeSession(jdbc, caller, clock.instant());
        return "Bearer "
                + issuer.issue(caller.id(), caller.organizationId(), caller.role(), session)
                        .value();
    }

    private Instant completedAt(TestIdentities.Organization org) {
        Timestamp at =
                jdbc.queryForObject(
                        "SELECT onboarding_completed_at FROM organization WHERE id = ?",
                        Timestamp.class,
                        org.id());
        return at == null ? null : at.toInstant();
    }

    private int audits(TestIdentities.Organization org) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ?"
                        + " AND action = 'ORGANIZATION_ONBOARDING_COMPLETED'",
                Integer.class,
                org.id());
    }
}
