package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.security.SecureTokens;
import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.MutableClock;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Organization MFA administration and promotion end to end (b2-7, B2-7/1, B2-7/3, B2-7/12, B2-7/14,
 * B2-7/17, B2-7/18, B2-7/23, B2-7/25; Spec 3.2, 3.3, 8.3): the policy change and who it newly
 * requires (sessions ended with {@code MFA_REQUIRED}, the acting Super Admin keeping theirs), the
 * selection for {@code REQUIRED_FOR_SELECTED_USERS}, resetting another person's MFA, promoting an
 * Employee to Admin, every authorization and ordering rule, tenant isolation, concurrency and the
 * audit rows.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class MfaAdministrationTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String POLICY = "/api/v1/organization/security/mfa-policy";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private AccessTokenIssuer issuer;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private MfaSecretCipher cipher;
    @Autowired private RecoveryCodes recoveryCodes;
    @Autowired private SecureTokens secureTokens;

    private String passwordHash;

    @BeforeEach
    void now() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (passwordHash == null) {
            passwordHash = passwordHasher.hash(PASSWORD);
        }
    }

    // ---------------------------------------------------------------------------------------
    // The organization's policy
    // ---------------------------------------------------------------------------------------

    @Test
    void aSuperAdminChangesThePolicyAfterAStepUpAndItIsAudited() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.DISABLED);
        Caller founder = steppedUp(caller(org, "SUPER_ADMIN"));

        MvcResult result = setPolicy(founder, "OPTIONAL");

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(policyOf(org)).isEqualTo("OPTIONAL");
        Map<String, Object> audit = lastAudit(org, "MFA_POLICY_CHANGED");
        assertThat(audit.get("actor_id")).isEqualTo(founder.employee().id().toString());
        assertThat(audit.get("target_type")).isEqualTo("ORGANIZATION");
        Map<String, Object> details = details(audit);
        assertThat(details.get("changes").toString())
                .contains("mfaPolicy")
                .contains("DISABLED")
                .contains("OPTIONAL");
        assertThat(attributes(details)).containsEntry("newlyRequired", 0);
    }

    @Test
    void settingTheCurrentPolicyAgainChangesNothing() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = steppedUp(caller(org, "SUPER_ADMIN"));

        assertThat(setPolicy(founder, "OPTIONAL").getResponse().getStatus()).isEqualTo(204);

        assertThat(auditCount(org, "MFA_POLICY_CHANGED")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "EMPLOYEE"})
    void onlyASuperAdminMayChangeThePolicy(String role) throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.DISABLED);
        Caller caller = steppedUp(caller(org, role));

        MvcResult result = setPolicy(caller, "REQUIRED_FOR_ALL");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result)).containsEntry("type", "urn:peoplehub:problem:forbidden");
        assertThat(policyOf(org)).isEqualTo("DISABLED");
    }

    @Test
    void changingThePolicyNeedsAFreshStepUp() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.DISABLED);
        Caller founder = caller(org, "SUPER_ADMIN");

        MvcResult result = setPolicy(founder, "OPTIONAL");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result)).containsEntry("type", "urn:peoplehub:problem:step-up-required");
        assertThat(policyOf(org)).isEqualTo("DISABLED");
    }

    @Test
    void anUnknownOrMissingPolicyIsA400() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.DISABLED);
        Caller founder = steppedUp(caller(org, "SUPER_ADMIN"));

        assertThat(setPolicy(founder, "MANDATORY").getResponse().getStatus()).isEqualTo(400);
        assertThat(send(put(POLICY), founder, Map.of()).getResponse().getStatus()).isEqualTo(400);
        assertThat(policyOf(org)).isEqualTo("DISABLED");
    }

    @Test
    void aPolicyThatNewlyRequiresMfaEndsTheSessionsOfThoseNotEnrolled() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller admin = caller(org, "ADMIN");
        Caller enrolledAdmin = caller(org, "ADMIN");
        enrolled(enrolledAdmin.employee());
        Caller employee = caller(org, "EMPLOYEE");

        assertThat(setPolicy(founder, "REQUIRED_FOR_ADMINS").getResponse().getStatus())
                .isEqualTo(204);

        assertThat(revokeReasons(admin)).containsOnly("MFA_REQUIRED");
        assertThat(meStatus(admin)).isEqualTo(401);
        assertThat(revokeReasons(enrolledAdmin)).isEmpty();
        assertThat(revokeReasons(employee)).isEmpty();
        assertThat(meStatus(employee)).isEqualTo(200);
        assertThat(attributes(details(lastAudit(org, "MFA_POLICY_CHANGED"))))
                .containsEntry("newlyRequired", 1);
        // No grace period: the next sign-in is the enrollment step.
        assertThat(body(login(admin.employee()))).containsEntry("mfaRequired", "ENROLL");
    }

    @Test
    void theActingSuperAdminKeepsThisSessionButMustEnrollBeforeProtectedActions() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = steppedUp(caller(org, "SUPER_ADMIN"));
        Caller founderElsewhere = session(founder.employee());

        assertThat(setPolicy(founder, "REQUIRED_FOR_ADMINS").getResponse().getStatus())
                .isEqualTo(204);

        assertThat(meStatus(founder)).isEqualTo(200);
        assertThat(meStatus(founderElsewhere)).isEqualTo(401);
        mvc.perform(get("/api/v1/me").header("Authorization", founder.bearer()))
                .andExpect(jsonPath("$.mfa.required").value(true))
                .andExpect(jsonPath("$.mfa.enabled").value(false));
        MvcResult protectedAction = setPolicy(founder, "OPTIONAL");
        assertThat(protectedAction.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(protectedAction))
                .containsEntry("type", "urn:peoplehub:problem:mfa-enrollment-required");
        assertThat(policyOf(org)).isEqualTo("REQUIRED_FOR_ADMINS");
    }

    @Test
    void movingBetweenRequiredPoliciesEndsOnlyTheNewlyCovered() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.REQUIRED_FOR_ADMINS);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller admin = caller(org, "ADMIN"); // covered before and after
        Caller employee = caller(org, "EMPLOYEE"); // newly covered

        assertThat(setPolicy(founder, "REQUIRED_FOR_ALL").getResponse().getStatus()).isEqualTo(204);

        assertThat(revokeReasons(admin)).isEmpty();
        assertThat(revokeReasons(employee)).containsOnly("MFA_REQUIRED");
    }

    @Test
    void relaxingThePolicyEndsNoSession() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.REQUIRED_FOR_ALL);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller employee = caller(org, "EMPLOYEE");

        assertThat(setPolicy(founder, "OPTIONAL").getResponse().getStatus()).isEqualTo(204);

        assertThat(revokeReasons(employee)).isEmpty();
    }

    @Test
    void switchingToSelectedUsersAppliesToPeopleSelectedBeforehand() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller selected = caller(org, "EMPLOYEE");
        Caller notSelected = caller(org, "ADMIN");
        select(selected.employee(), true);

        assertThat(setPolicy(founder, "REQUIRED_FOR_SELECTED_USERS").getResponse().getStatus())
                .isEqualTo(204);

        assertThat(revokeReasons(selected)).containsOnly("MFA_REQUIRED");
        assertThat(revokeReasons(notSelected)).isEmpty();
    }

    @Test
    void aPolicyChangeNeverTouchesAnotherOrganization() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        TestIdentities.Organization other = organization(MfaPolicy.OPTIONAL);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller elsewhere = caller(other, "EMPLOYEE");

        assertThat(setPolicy(founder, "REQUIRED_FOR_ALL").getResponse().getStatus()).isEqualTo(204);

        assertThat(policyOf(other)).isEqualTo("OPTIONAL");
        assertThat(revokeReasons(elsewhere)).isEmpty();
        assertThat(auditCount(other, "MFA_POLICY_CHANGED")).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Selecting people
    // ---------------------------------------------------------------------------------------

    @Test
    void selectingSomeoneUnderSelectedUsersEndsTheirSessions() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.REQUIRED_FOR_SELECTED_USERS);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller employee = caller(org, "EMPLOYEE");

        MvcResult result = setRequired(founder, employee.employee().id(), true);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(selected(employee)).isTrue();
        assertThat(revokeReasons(employee)).containsOnly("MFA_REQUIRED");
        Map<String, Object> details = details(lastAudit(org, "MFA_SELECTION_CHANGED"));
        assertThat(details.get("changes").toString()).contains("mfaRequired");
        assertThat(attributes(details)).containsEntry("newlyRequired", true);
        assertThat(body(login(employee.employee()))).containsEntry("mfaRequired", "ENROLL");
    }

    @Test
    void aSelectionUnderAnotherPolicyIsOnlyPreparation() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller employee = caller(org, "EMPLOYEE");

        assertThat(setRequired(founder, employee.employee().id(), true).getResponse().getStatus())
                .isEqualTo(204);

        assertThat(selected(employee)).isTrue();
        assertThat(revokeReasons(employee)).isEmpty();
        assertThat(body(login(employee.employee()))).containsKey("accessToken");
    }

    @Test
    void unselectingOrSelectingSomeoneEnrolledEndsNoSession() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.REQUIRED_FOR_SELECTED_USERS);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller enrolledOne = caller(org, "EMPLOYEE");
        enrolled(enrolledOne.employee());
        Caller unselected = caller(org, "EMPLOYEE");
        select(unselected.employee(), true);

        setRequired(founder, enrolledOne.employee().id(), true);
        setRequired(founder, unselected.employee().id(), false);

        assertThat(revokeReasons(enrolledOne)).isEmpty();
        assertThat(revokeReasons(unselected)).isEmpty();
        assertThat(selected(unselected)).isFalse();
    }

    @Test
    void aSuperAdminSelectingThemselvesKeepsThisSession() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.REQUIRED_FOR_SELECTED_USERS);
        Caller founder = steppedUp(caller(org, "SUPER_ADMIN"));
        Caller founderElsewhere = session(founder.employee());

        assertThat(setRequired(founder, founder.employee().id(), true).getResponse().getStatus())
                .isEqualTo(204);

        assertThat(meStatus(founder)).isEqualTo(200);
        assertThat(meStatus(founderElsewhere)).isEqualTo(401);
    }

    @Test
    void selectingNeedsASuperAdminAStepUpAndATargetInTheOrganization() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = caller(org, "SUPER_ADMIN");
        Caller admin = steppedUp(caller(org, "ADMIN"));
        Caller employee = caller(org, "EMPLOYEE");
        Caller elsewhere = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");

        assertThat(setRequired(admin, employee.employee().id(), true).getResponse().getStatus())
                .isEqualTo(403);
        MvcResult noStepUp = setRequired(founder, employee.employee().id(), true);
        assertThat(body(noStepUp)).containsEntry("type", "urn:peoplehub:problem:step-up-required");
        // Another organization's id and an unknown id are the same 404, before the step-up.
        MvcResult foreign = setRequired(founder, elsewhere.employee().id(), true);
        MvcResult unknown = setRequired(founder, UUID.randomUUID(), true);
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(foreign).get("detail")).isEqualTo(body(unknown).get("detail"));
        steppedUp(founder);
        assertThat(send(put(requiredPath(employee)), founder, Map.of()).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(selected(employee)).isFalse();
        assertThat(selected(elsewhere)).isFalse();
    }

    // ---------------------------------------------------------------------------------------
    // Resetting another person's MFA
    // ---------------------------------------------------------------------------------------

    @Test
    void anAdminResetsAnEmployeesMfaCompletely() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller admin = steppedUp(caller(org, "ADMIN"));
        Caller employee = caller(org, "EMPLOYEE");
        enrolled(employee.employee());
        jdbc.update(
                "UPDATE employee SET mfa_totp_pending_secret = 'k:pending' WHERE id = ?",
                employee.employee().id());
        String openChallenge = (String) body(login(employee.employee())).get("challengeToken");

        MvcResult result = reset(admin, employee.employee().id());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT mfa_enabled, mfa_totp_secret, mfa_totp_pending_secret,"
                                        + " mfa_enrolled_at FROM employee WHERE id = ?",
                                employee.employee().id()))
                .containsEntry("mfa_enabled", false)
                .containsEntry("mfa_totp_secret", null)
                .containsEntry("mfa_totp_pending_secret", null)
                .containsEntry("mfa_enrolled_at", null);
        assertThat(unusedRecoveryCodes(employee)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM mfa_recovery_code WHERE employee_id = ?",
                                Integer.class,
                                employee.employee().id()))
                .isEqualTo(10); // invalidated, never deleted
        assertThat(
                        jdbc.queryForObject(
                                "SELECT invalidated_at IS NOT NULL FROM mfa_challenge"
                                        + " WHERE token_hash = ?",
                                Boolean.class,
                                secureTokens.hash(openChallenge)))
                .isTrue();
        assertThat(revokeReasons(employee)).containsOnly("MFA_RESET");
        assertThat(attributes(details(lastAudit(org, "MFA_RESET"))))
                .containsEntry("role", "EMPLOYEE")
                .containsEntry("codesInvalidated", 10);
        // Not required under OPTIONAL: the password alone signs in now.
        assertThat(body(login(employee.employee()))).containsKey("accessToken");
    }

    @Test
    void afterAResetARequiredPersonEnrollsAgainAtTheNextSignIn() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.REQUIRED_FOR_ALL);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller admin = caller(org, "ADMIN");
        enrolled(admin.employee());

        assertThat(reset(founder, admin.employee().id()).getResponse().getStatus()).isEqualTo(204);

        assertThat(body(login(admin.employee()))).containsEntry("mfaRequired", "ENROLL");
    }

    @Test
    void whoMayResetWhom() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller otherSuperAdmin = caller(org, "SUPER_ADMIN");
        Caller admin = steppedUp(caller(org, "ADMIN"));
        Caller otherAdmin = caller(org, "ADMIN");
        Caller employee = steppedUp(caller(org, "EMPLOYEE"));
        Caller colleague = caller(org, "EMPLOYEE");
        for (Caller target : List.of(otherSuperAdmin, otherAdmin, colleague)) {
            enrolled(target.employee());
        }

        // An Admin: Employees only.
        assertThat(status(reset(admin, otherAdmin.employee().id()))).isEqualTo(403);
        assertThat(status(reset(admin, otherSuperAdmin.employee().id()))).isEqualTo(403);
        assertThat(status(reset(admin, admin.employee().id()))).isEqualTo(403);
        // An Employee: nobody.
        assertThat(status(reset(employee, colleague.employee().id()))).isEqualTo(403);
        // A Super Admin: never a Super Admin, never themselves.
        assertThat(status(reset(founder, otherSuperAdmin.employee().id()))).isEqualTo(403);
        assertThat(status(reset(founder, founder.employee().id()))).isEqualTo(403);
        for (Caller target : List.of(otherSuperAdmin, otherAdmin, colleague)) {
            assertThat(mfaEnabled(target)).isTrue();
        }
        // Allowed: a Super Admin an Admin's, an Admin an Employee's.
        assertThat(status(reset(founder, otherAdmin.employee().id()))).isEqualTo(204);
        assertThat(status(reset(admin, colleague.employee().id()))).isEqualTo(204);
    }

    @Test
    void resettingNeedsAStepUpATargetInTheOrganizationAndAnEnrollment() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller admin = caller(org, "ADMIN");
        Caller employee = caller(org, "EMPLOYEE");
        enrolled(employee.employee());
        Caller notEnrolled = caller(org, "EMPLOYEE");
        Caller elsewhere = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");
        enrolled(elsewhere.employee());

        MvcResult noStepUp = reset(admin, employee.employee().id());
        assertThat(body(noStepUp)).containsEntry("type", "urn:peoplehub:problem:step-up-required");
        MvcResult foreign = reset(admin, elsewhere.employee().id());
        MvcResult unknown = reset(admin, UUID.randomUUID());
        assertThat(status(foreign)).isEqualTo(404);
        assertThat(status(unknown)).isEqualTo(404);
        assertThat(body(foreign).get("detail")).isEqualTo(body(unknown).get("detail"));
        steppedUp(admin);
        MvcResult nothing = reset(admin, notEnrolled.employee().id());
        assertThat(status(nothing)).isEqualTo(409);
        assertThat(mfaEnabled(employee)).isTrue();
        assertThat(mfaEnabled(elsewhere)).isTrue();
        assertThat(revokeReasons(elsewhere)).isEmpty();
    }

    @Test
    void twoParallelResetsResetOnce() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller admin = steppedUp(caller(org, "ADMIN"));
        Caller employee = caller(org, "EMPLOYEE");
        enrolled(employee.employee());

        List<Integer> statuses =
                parallel(
                        () -> status(reset(founder, employee.employee().id())),
                        () -> status(reset(admin, employee.employee().id())));

        assertThat(statuses).containsExactlyInAnyOrder(204, 409);
        assertThat(auditCount(org, "MFA_RESET")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // Promotion
    // ---------------------------------------------------------------------------------------

    @Test
    void aSuperAdminPromotesAnEmployeeAndTheirSessionsEnd() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = steppedUp(caller(org, "SUPER_ADMIN"));
        Caller employee = caller(org, "EMPLOYEE");

        MvcResult result = promote(founder, employee.employee().id());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(roleOf(employee)).isEqualTo("ADMIN");
        assertThat(revokeReasons(employee)).containsOnly("ROLE_CHANGED");
        assertThat(meStatus(employee)).isEqualTo(401);
        assertThat(attributes(details(lastAudit(org, "EMPLOYEE_PROMOTED"))))
                .containsEntry("fromRole", "EMPLOYEE")
                .containsEntry("toRole", "ADMIN");
        // MFA is not a precondition and, under OPTIONAL, not required afterwards either.
        Map<String, Object> signedIn = body(login(employee.employee()));
        assertThat(signedIn).containsKey("accessToken");
        mvc.perform(
                        get("/api/v1/me")
                                .header("Authorization", "Bearer " + signedIn.get("accessToken")))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void aPromotedAdminEnrollsAtTheNextSignInWhenThePolicyRequiresIt() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.REQUIRED_FOR_ADMINS);
        Caller founder = enrolledAndSteppedUp(caller(org, "SUPER_ADMIN"));
        Caller notEnrolled = caller(org, "EMPLOYEE");
        Caller alreadyEnrolled = caller(org, "EMPLOYEE");
        enrolled(alreadyEnrolled.employee());

        assertThat(status(promote(founder, notEnrolled.employee().id()))).isEqualTo(204);
        assertThat(status(promote(founder, alreadyEnrolled.employee().id()))).isEqualTo(204);

        assertThat(body(login(notEnrolled.employee()))).containsEntry("mfaRequired", "ENROLL");
        assertThat(body(login(alreadyEnrolled.employee())))
                .containsEntry("mfaRequired", "CHALLENGE");
    }

    @Test
    void promotionFollowsTheApprovedCheckOrder() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = caller(org, "SUPER_ADMIN");
        Caller admin = steppedUp(caller(org, "ADMIN"));
        Caller employee = caller(org, "EMPLOYEE");
        Caller otherAdmin = caller(org, "ADMIN");
        Caller elsewhere = caller(organization(MfaPolicy.OPTIONAL), "EMPLOYEE");

        // Not a Super Admin: 403, whatever the id.
        assertThat(status(promote(admin, employee.employee().id()))).isEqualTo(403);
        assertThat(status(promote(admin, UUID.randomUUID()))).isEqualTo(403);
        // Unknown or another organization's: the same 404, before the step-up.
        MvcResult foreign = promote(founder, elsewhere.employee().id());
        MvcResult unknown = promote(founder, UUID.randomUUID());
        assertThat(status(foreign)).isEqualTo(404);
        assertThat(status(unknown)).isEqualTo(404);
        assertThat(body(foreign).get("detail")).isEqualTo(body(unknown).get("detail"));
        // Themselves: 403, before the step-up.
        MvcResult self = promote(founder, founder.employee().id());
        assertThat(body(self)).containsEntry("type", "urn:peoplehub:problem:forbidden");
        // Then the step-up, before the target's state.
        MvcResult noStepUp = promote(founder, otherAdmin.employee().id());
        assertThat(body(noStepUp)).containsEntry("type", "urn:peoplehub:problem:step-up-required");
        steppedUp(founder);
        assertThat(status(promote(founder, otherAdmin.employee().id()))).isEqualTo(409);
        assertThat(roleOf(employee)).isEqualTo("EMPLOYEE");
        assertThat(roleOf(elsewhere)).isEqualTo("EMPLOYEE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVITED", "DEACTIVATED"})
    void onlyAnActiveEmployeeCanBePromoted(String status) throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = steppedUp(caller(org, "SUPER_ADMIN"));
        Caller employee = caller(org, "EMPLOYEE");
        jdbc.update(
                "UPDATE employee SET status = ? WHERE id = ?", status, employee.employee().id());

        MvcResult result = promote(founder, employee.employee().id());

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(roleOf(employee)).isEqualTo("EMPLOYEE");
    }

    @Test
    void twoParallelPromotionsPromoteOnce() throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = steppedUp(caller(org, "SUPER_ADMIN"));
        Caller other = steppedUp(caller(org, "SUPER_ADMIN"));
        Caller employee = caller(org, "EMPLOYEE");

        List<Integer> statuses =
                parallel(
                        () -> status(promote(founder, employee.employee().id())),
                        () -> status(promote(other, employee.employee().id())));

        assertThat(statuses).containsExactlyInAnyOrder(204, 409);
        assertThat(auditCount(org, "EMPLOYEE_PROMOTED")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // Secrecy and documentation
    // ---------------------------------------------------------------------------------------

    @Test
    void noPasswordOrCodeReachesTheLogs(CapturedOutput output) throws Exception {
        TestIdentities.Organization org = organization(MfaPolicy.OPTIONAL);
        Caller founder = caller(org, "SUPER_ADMIN");
        byte[] secret = enrolled(founder.employee());
        String code = Totp.code(secret, Totp.step(clock.instant()), 6);
        stepUp(founder, code);
        Caller employee = caller(org, "EMPLOYEE");
        enrolled(employee.employee());
        setPolicy(founder, "REQUIRED_FOR_ALL");
        setRequired(founder, employee.employee().id(), true);
        reset(founder, employee.employee().id());
        promote(founder, employee.employee().id());

        String logs = output.getAll();
        assertThat(logs).doesNotContain(PASSWORD).doesNotContain("\"" + code + "\"");
        assertThat(logs).doesNotContain(Base32.encode(secret));
    }

    @Test
    void theOpenApiDocumentDescribesTheAdministrationEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['" + POLICY + "'].put.security").isArray())
                .andExpect(jsonPath("$.paths['" + POLICY + "'].put.responses['403']").exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/super-admin/employees/{id}/mfa-required'].put.responses['404']")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/admin/employees/{id}/mfa/reset'].post.responses['409']")
                                .exists())
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/super-admin/employees/{id}/promote-admin'].post.responses['409']")
                                .exists());
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private record Caller(TestIdentities.Employee employee, UUID session, String bearer) {}

    private TestIdentities.Organization organization(MfaPolicy policy) {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        jdbc.update("UPDATE organization SET mfa_policy = ? WHERE id = ?", policy.name(), org.id());
        return org;
    }

    private Caller caller(TestIdentities.Organization org, String role) {
        return session(TestIdentities.activeEmployee(jdbc, org, role, passwordHash));
    }

    private Caller session(TestIdentities.Employee employee) {
        UUID session = TestIdentities.activeSession(jdbc, employee, clock.instant());
        String token =
                issuer.issue(employee.id(), employee.organizationId(), employee.role(), session)
                        .value();
        return new Caller(employee, session, "Bearer " + token);
    }

    /** A step-up of this session, recorded directly (the step-up flow is MfaStepUpTest's job). */
    private Caller steppedUp(Caller caller) {
        boolean enabled =
                jdbc.queryForObject(
                        "SELECT mfa_enabled FROM employee WHERE id = ?",
                        Boolean.class,
                        caller.employee().id());
        jdbc.update(
                "INSERT INTO session_step_up (organization_id, employee_id, session_id, method)"
                        + " VALUES (?, ?, ?, ?)",
                caller.employee().organizationId(),
                caller.employee().id(),
                caller.session(),
                enabled ? "PASSWORD_AND_TOTP" : "PASSWORD");
        return caller;
    }

    private Caller enrolledAndSteppedUp(Caller caller) {
        enrolled(caller.employee());
        return steppedUp(caller);
    }

    private void stepUp(Caller caller, String code) throws Exception {
        MvcResult result =
                send(
                        post("/api/v1/me/step-up"),
                        caller,
                        Map.of("password", PASSWORD, "code", code));
        assertThat(status(result)).isEqualTo(204);
    }

    private byte[] enrolled(TestIdentities.Employee employee) {
        byte[] secret = Totp.newSecret();
        jdbc.update(
                "UPDATE employee SET mfa_enabled = true, mfa_totp_secret = ?, mfa_enrolled_at = ?"
                        + " WHERE id = ?",
                cipher.encrypt(secret, employee.organizationId(), employee.id()),
                Timestamp.from(clock.instant()),
                employee.id());
        for (String code : recoveryCodes.generate()) {
            jdbc.update(
                    "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash)"
                            + " VALUES (?, ?, ?)",
                    employee.organizationId(),
                    employee.id(),
                    secureTokens.hash(code.replace("-", "")));
        }
        return secret;
    }

    private void select(TestIdentities.Employee employee, boolean required) {
        jdbc.update("UPDATE employee SET mfa_required = ? WHERE id = ?", required, employee.id());
    }

    private MvcResult setPolicy(Caller caller, String policy) throws Exception {
        return send(put(POLICY), caller, Map.of("policy", policy));
    }

    private static String requiredPath(Caller target) {
        return "/api/v1/super-admin/employees/" + target.employee().id() + "/mfa-required";
    }

    private MvcResult setRequired(Caller caller, UUID target, boolean required) throws Exception {
        return send(
                put("/api/v1/super-admin/employees/" + target + "/mfa-required"),
                caller,
                Map.of("required", required));
    }

    private MvcResult reset(Caller caller, UUID target) throws Exception {
        return mvc.perform(
                        post("/api/v1/admin/employees/" + target + "/mfa/reset")
                                .header("Authorization", caller.bearer()))
                .andReturn();
    }

    private MvcResult promote(Caller caller, UUID target) throws Exception {
        return mvc.perform(
                        post("/api/v1/super-admin/employees/" + target + "/promote-admin")
                                .header("Authorization", caller.bearer()))
                .andReturn();
    }

    private MvcResult send(
            MockHttpServletRequestBuilder request, Caller caller, Map<String, ?> body)
            throws Exception {
        return mvc.perform(
                        request.header("Authorization", caller.bearer())
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(new HashMap<>(body))))
                .andReturn();
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

    private int meStatus(Caller caller) throws Exception {
        return status(
                mvc.perform(get("/api/v1/me").header("Authorization", caller.bearer()))
                        .andReturn());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private List<Integer> parallel(Task first, Task second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Task task : List.of(first, second)) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return task.run();
                                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Task {
        int run() throws Exception;
    }

    private List<String> revokeReasons(Caller caller) {
        return jdbc.queryForList(
                "SELECT revoke_reason FROM refresh_token WHERE employee_id = ? AND revoked",
                String.class,
                caller.employee().id());
    }

    private String policyOf(TestIdentities.Organization org) {
        return jdbc.queryForObject(
                "SELECT mfa_policy FROM organization WHERE id = ?", String.class, org.id());
    }

    private String roleOf(Caller caller) {
        return jdbc.queryForObject(
                "SELECT role FROM employee WHERE id = ?", String.class, caller.employee().id());
    }

    private boolean selected(Caller caller) {
        return jdbc.queryForObject(
                "SELECT mfa_required FROM employee WHERE id = ?",
                Boolean.class,
                caller.employee().id());
    }

    private boolean mfaEnabled(Caller caller) {
        return jdbc.queryForObject(
                "SELECT mfa_enabled FROM employee WHERE id = ?",
                Boolean.class,
                caller.employee().id());
    }

    private int unusedRecoveryCodes(Caller caller) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM mfa_recovery_code WHERE employee_id = ?"
                        + " AND used_at IS NULL AND invalidated_at IS NULL",
                Integer.class,
                caller.employee().id());
    }

    private int auditCount(TestIdentities.Organization org, String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ? AND action = ?",
                Integer.class,
                org.id(),
                action);
    }

    private Map<String, Object> lastAudit(TestIdentities.Organization org, String action) {
        return jdbc.queryForMap(
                "SELECT actor_id, target_type, details::text AS details FROM audit_log"
                        + " WHERE organization_id = ? AND action = ? ORDER BY id DESC LIMIT 1",
                org.id(),
                action);
    }

    private static Map<String, Object> details(Map<String, Object> auditRow) throws Exception {
        return JSON.readValue((String) auditRow.get("details"), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> details) {
        return (Map<String, Object>) details.get("attributes");
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }
}
