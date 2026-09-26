package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.common.database.TenantContext;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TenantWriteProbe;
import com.peoplehub.support.TestIdentities;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MFA step of a sign-in binds its transaction to the challenge's organization before it writes
 * (b2-8 C3; O1, O3): the required enrollment ({@code ENROLL}) and the code challenge ({@code
 * CHALLENGE}) both resolve the challenge token through V24, and every row they write carries the
 * organization the transaction is bound to. See {@code PreTenantFlowBindingTest} for the other
 * public flows and for how {@link TenantWriteProbe} works.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
class MfaSignInTenantBindingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Probe-pass " + UUID.randomUUID();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate fixture;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private MutableClock clock;

    private TenantWriteProbe probe;

    @BeforeEach
    void installTheProbe() {
        probe = new TenantWriteProbe(fixture);
        probe.install();
    }

    @AfterEach
    void removeTheProbe() {
        probe.remove();
    }

    @Test
    void theEnrollmentAndTheChallengeStepsBindEveryWrite() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(fixture);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(
                        fixture, org, "EMPLOYEE", passwordHasher.hash(PASSWORD));
        fixture.update(
                "UPDATE organization SET mfa_policy = 'REQUIRED_FOR_ALL' WHERE id = ?", org.id());

        // Required enrollment at sign-in.
        Map<String, Object> first = body(login(employee));
        assertThat(first).containsEntry("mfaRequired", "ENROLL");
        String enrollToken = (String) first.get("challengeToken");
        MvcResult started =
                send(post("/api/v1/auth/mfa/enroll"), Map.of("challengeToken", enrollToken));
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        byte[] secret = Base32.decode((String) body(started).get("secret")).orElseThrow();
        MvcResult enrolled =
                send(
                        post("/api/v1/auth/mfa/enroll/confirm"),
                        Map.of("challengeToken", enrollToken, "code", code(secret)));
        assertThat(enrolled.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(enrolled)).containsKey("accessToken");

        // The next sign-in is challenged; a code of the next time step completes it.
        clock.advance(Duration.ofSeconds(30));
        Map<String, Object> second = body(login(employee));
        assertThat(second).containsEntry("mfaRequired", "CHALLENGE");
        MvcResult challenged =
                send(
                        post("/api/v1/auth/mfa/challenge"),
                        Map.of(
                                "challengeToken",
                                (String) second.get("challengeToken"),
                                "code",
                                code(secret)));
        assertThat(challenged.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(challenged)).containsKey("accessToken");

        assertThat(probe.unboundWrites()).isEmpty();
        assertThat(probe.boundWrites(org.id()))
                .contains(
                        "mfa_challenge INSERT",
                        "mfa_challenge UPDATE",
                        "employee UPDATE",
                        "refresh_token INSERT",
                        "audit_log INSERT");
    }

    @Test
    void aWrongCodeIsStillCountedInsideTheChallengesOrganization() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(fixture);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(
                        fixture, org, "EMPLOYEE", passwordHasher.hash(PASSWORD));
        fixture.update(
                "UPDATE organization SET mfa_policy = 'REQUIRED_FOR_ALL' WHERE id = ?", org.id());
        String enrollToken = (String) body(login(employee)).get("challengeToken");

        MvcResult wrong =
                send(
                        post("/api/v1/auth/mfa/enroll/confirm"),
                        Map.of("challengeToken", enrollToken, "code", "000000"));

        // Starting enrollment was never called, so there is no secret to check against: the
        // challenge simply ends, and whatever was written is bound to the organization.
        assertThat(wrong.getResponse().getStatus()).isIn(400, 401);
        assertThat(probe.unboundWrites()).isEmpty();
    }

    private MvcResult login(TestIdentities.Employee employee) throws Exception {
        MvcResult result =
                send(
                        post("/api/v1/auth/login"),
                        Map.of(
                                "organization", employee.organizationLoginKey(),
                                "email", employee.email(),
                                "password", PASSWORD));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result;
    }

    private String code(byte[] secret) {
        return Totp.code(secret, Totp.step(clock.instant()), 6);
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        MvcResult result =
                mvc.perform(
                                request.contentType("application/json")
                                        .content(JSON.writeValueAsString(body)))
                        .andReturn();
        assertThat(TenantContext.current()).as("no tenant left on the thread").isEmpty();
        return result;
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }
}
