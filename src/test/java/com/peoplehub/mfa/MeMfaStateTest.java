package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code mfa} object of {@code GET /me} and the server-side reminder (b2-7, B2-7/1, B2-7/20,
 * B2-7/27; MFA/3, MFA/7; Spec 8.3): coverage per policy, role and selection, the reminder's
 * eligibility and its dismissal interval ({@code P7D} by default), all on a controllable clock.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
class MeMfaStateTest {

    private static final String DISMISS = "/api/v1/me/mfa/reminder/dismiss";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;
    @Autowired private MutableClock clock;

    @BeforeEach
    void now() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void aNewOrganizationHasMfaDisabledAndNobodyIsRemindedOrRequired() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee founder =
                TestIdentities.activeEmployee(jdbc, org, "SUPER_ADMIN", null);

        assertThat(mfa(founder))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "enabled", false,
                                "required", false,
                                "policy", "DISABLED",
                                "showReminder", false));
    }

    @ParameterizedTest(name = "{0}, {1}, selected={2} -> required={3}, reminder={4}")
    @CsvSource({
        "DISABLED, SUPER_ADMIN, true, false, false",
        "OPTIONAL, EMPLOYEE, false, false, true",
        "OPTIONAL, SUPER_ADMIN, true, false, true",
        "REQUIRED_FOR_ADMINS, EMPLOYEE, false, false, true",
        "REQUIRED_FOR_ADMINS, ADMIN, false, true, false",
        "REQUIRED_FOR_ADMINS, SUPER_ADMIN, false, true, false",
        "REQUIRED_FOR_SELECTED_USERS, EMPLOYEE, true, true, false",
        "REQUIRED_FOR_SELECTED_USERS, ADMIN, false, false, true",
        "REQUIRED_FOR_ALL, EMPLOYEE, false, true, false"
    })
    void requiredAndReminderFollowThePolicy(
            MfaPolicy policy, String role, boolean selected, boolean required, boolean showReminder)
            throws Exception {
        TestIdentities.Organization org = organization(policy);
        TestIdentities.Employee employee = TestIdentities.activeEmployee(jdbc, org, role, null);
        jdbc.update("UPDATE employee SET mfa_required = ? WHERE id = ?", selected, employee.id());

        assertThat(mfa(employee))
                .containsEntry("enabled", false)
                .containsEntry("required", required)
                .containsEntry("policy", policy.name())
                .containsEntry("showReminder", showReminder);
    }

    @Test
    void anEnrolledPersonShowsEnabledAndIsNotReminded() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.REQUIRED_FOR_ADMINS);
        TestIdentities.Employee admin = TestIdentities.activeEmployee(jdbc, org, "ADMIN", null);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        enrolled(admin);
        enrolled(employee);

        assertThat(mfa(admin))
                .containsEntry("enabled", true)
                .containsEntry("required", true)
                .containsEntry("showReminder", false);
        assertThat(mfa(employee))
                .containsEntry("enabled", true)
                .containsEntry("required", false)
                .containsEntry("showReminder", false);
    }

    @Test
    void anEnrollmentKeepsShowingEnabledUnderDisabled() throws Exception {
        // B2-7/2: DISABLED hides enrollment but never switches off an existing one.
        TestIdentities.Organization org = organization(MfaPolicy.DISABLED);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        enrolled(employee);

        assertThat(mfa(employee))
                .containsEntry("enabled", true)
                .containsEntry("required", false)
                .containsEntry("showReminder", false);
    }

    @Test
    void aPendingEnrollmentIsNotEnabled() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        assertThat(
                        mvc.perform(
                                        post("/api/v1/me/mfa/enroll")
                                                .header("Authorization", bearer(employee)))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);

        assertThat(mfa(employee))
                .containsEntry("enabled", false)
                .containsEntry("showReminder", true);
    }

    @Test
    void aDismissedReminderReturnsAfterTheInterval() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        assertThat(mfa(employee)).containsEntry("showReminder", true);
        Instant dismissedAt = clock.instant();

        MvcResult dismissed = dismiss(employee);

        assertThat(dismissed.getResponse().getStatus()).isEqualTo(204);
        assertThat(dismissed.getResponse().getContentAsString()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT mfa_reminder_dismissed_at FROM employee WHERE id = ?",
                                        Timestamp.class,
                                        employee.id())
                                .toInstant())
                .isEqualTo(dismissedAt);
        assertThat(mfa(employee)).containsEntry("showReminder", false);

        clock.advance(Duration.ofDays(7).minusSeconds(1));
        assertThat(mfa(employee)).containsEntry("showReminder", false);
        clock.advance(Duration.ofSeconds(1));
        assertThat(mfa(employee)).containsEntry("showReminder", true);
    }

    @Test
    void dismissingTwiceIsFineAndRestartsTheInterval() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        assertThat(dismiss(employee).getResponse().getStatus()).isEqualTo(204);
        clock.advance(Duration.ofDays(3));
        assertThat(dismiss(employee).getResponse().getStatus()).isEqualTo(204);

        clock.advance(Duration.ofDays(5)); // 8 days after the first, 5 after the second
        assertThat(mfa(employee)).containsEntry("showReminder", false);
        clock.advance(Duration.ofDays(2));
        assertThat(mfa(employee)).containsEntry("showReminder", true);
    }

    @Test
    void dismissingChangesOnlyTheCallersOwnRow() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        TestIdentities.Employee colleague =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        TestIdentities.Organization other = organization(MfaPolicy.OPTIONAL);
        TestIdentities.Employee elsewhere =
                TestIdentities.activeEmployee(jdbc, other, "EMPLOYEE", null);

        assertThat(dismiss(employee).getResponse().getStatus()).isEqualTo(204);

        assertThat(mfa(colleague)).containsEntry("showReminder", true);
        assertThat(mfa(elsewhere)).containsEntry("showReminder", true);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM employee WHERE id IN (?, ?)"
                                        + " AND mfa_reminder_dismissed_at IS NOT NULL",
                                Integer.class,
                                colleague.id(),
                                elsewhere.id()))
                .isZero();
    }

    @Test
    void dismissingIsNotAudited() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);

        dismiss(employee);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_log WHERE organization_id = ?",
                                Integer.class,
                                org.id()))
                .isZero();
    }

    @Test
    void theOpenApiDocumentDescribesTheMfaObject() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.MeResponse.properties.mfa").exists())
                .andExpect(jsonPath("$.components.schemas.Mfa.properties.showReminder").exists());
    }

    private TestIdentities.Organization organization(MfaPolicy policy) {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        jdbc.update("UPDATE organization SET mfa_policy = ? WHERE id = ?", policy.name(), org.id());
        return org;
    }

    /** A confirmed enrollment written directly (the flow itself is MfaEnrollmentTest's job). */
    private void enrolled(TestIdentities.Employee employee) {
        jdbc.update(
                "UPDATE employee SET mfa_enabled = true, mfa_totp_secret = 'k:x',"
                        + " mfa_enrolled_at = now() WHERE id = ?",
                employee.id());
    }

    private String bearer(TestIdentities.Employee caller) {
        UUID session = TestIdentities.activeSession(jdbc, caller, clock.instant());
        return "Bearer "
                + issuer.issue(caller.id(), caller.organizationId(), caller.role(), session)
                        .value();
    }

    private MvcResult dismiss(TestIdentities.Employee caller) throws Exception {
        return mvc.perform(post(DISMISS).header("Authorization", bearer(caller))).andReturn();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mfa(TestIdentities.Employee caller) throws Exception {
        MvcResult result =
                mvc.perform(get("/api/v1/me").header("Authorization", bearer(caller))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> body =
                JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
        return (Map<String, Object>) body.get("mfa");
    }
}
