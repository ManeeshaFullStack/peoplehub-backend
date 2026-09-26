package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.JWTClaimsSet;
import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestIdentities;
import com.peoplehub.support.TestJwtKeys;
import com.peoplehub.support.TestTokens;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bearer-token authentication end to end through {@code GET /api/v1/me} (b2-3, B2-3/1-B2-3/4,
 * B2-3/14, B2-3/16, B2-3/18): a valid token works; every forged, malformed, expired, revoked or
 * foreign token gets the <em>same</em> 401 body, so the caller never learns which check failed.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AccessTokenAuthenticationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;

    private TestIdentities.Employee employee;
    private UUID session;
    private Instant now;

    @BeforeEach
    void anActiveEmployeeWithASession() {
        now = Instant.now();
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        employee = TestIdentities.activeEmployee(jdbc, org, "SUPER_ADMIN", null);
        session = TestIdentities.activeSession(jdbc, employee, now);
    }

    private MvcResult me(String token) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    /** The one and only rejection: 401, generic detail, nothing about why. */
    private void assertRejected(String token) throws Exception {
        MvcResult result = me(token);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        Map<String, Object> body = body(result);
        assertThat(body)
                .containsOnlyKeys("type", "title", "status", "detail", "instance", "correlationId")
                .containsEntry("type", "urn:peoplehub:problem:unauthorized")
                .containsEntry("detail", ProblemAuthenticationEntryPoint.DETAIL);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContainIgnoringCase("expired")
                .doesNotContainIgnoringCase("signature")
                .doesNotContainIgnoringCase("jwt")
                .doesNotContain(employee.email());
    }

    // ---- the happy path ----

    @Test
    void anIssuedTokenAuthenticatesAndMeReturnsOnlyTheCallersOwnProfile() throws Exception {
        String token =
                issuer.issue(employee.id(), employee.organizationId(), "SUPER_ADMIN", session)
                        .value();

        MvcResult result = me(token);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> body = body(result);
        assertThat(body)
                // firstName and welcomeSeenAt: b2-4 (B2-4/O12); mfa: b2-7 (B2-7/20).
                .containsOnlyKeys(
                        "id",
                        "name",
                        "firstName",
                        "email",
                        "role",
                        "status",
                        "joinDate",
                        "welcomeSeenAt",
                        "organization",
                        "mfa")
                .containsEntry("id", employee.id().toString())
                .containsEntry("firstName", "Jane")
                .containsEntry("welcomeSeenAt", null)
                .containsEntry("email", employee.email())
                .containsEntry("role", "SUPER_ADMIN")
                .containsEntry("status", "ACTIVE")
                .containsEntry("joinDate", "2026-01-05");
        assertThat(body.get("organization"))
                .isEqualTo(
                        Map.of(
                                "name",
                                "Acme " + employee.organizationLoginKey(),
                                "timezone",
                                "Asia/Kolkata",
                                "onboardingCompleted",
                                false));
        // A new organization's policy is DISABLED (MFA/3).
        assertThat(body.get("mfa"))
                .isEqualTo(
                        Map.of(
                                "enabled", false,
                                "required", false,
                                "policy", "DISABLED",
                                "showReminder", false));
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(employee.organizationId().toString())
                .doesNotContain("password");
    }

    @Test
    void theIssuedTokenCarriesExactlyTheApprovedClaimsAndNoPersonalData() throws Exception {
        String token =
                issuer.issue(employee.id(), employee.organizationId(), "SUPER_ADMIN", session)
                        .value();
        com.nimbusds.jwt.SignedJWT jwt = com.nimbusds.jwt.SignedJWT.parse(token);

        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(TestJwtKeys.KEY_ID);
        assertThat(jwt.getJWTClaimsSet().getClaims().keySet())
                .containsExactlyInAnyOrder(
                        "iss", "aud", "sub", "org", "role", "sid", "jti", "iat", "exp");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(employee.id().toString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("org"))
                .isEqualTo(employee.organizationId().toString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("sid")).isEqualTo(session.toString());
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("peoplehub-api");
        assertThat(
                        jwt.getJWTClaimsSet().getExpirationTime().getTime()
                                - jwt.getJWTClaimsSet().getIssueTime().getTime())
                .isEqualTo(15 * 60 * 1000L);
        assertThat(jwt.getPayload().toString())
                .doesNotContain(employee.email())
                .doesNotContain("Jane");
    }

    @Test
    void theRoleComesFromTheDatabaseNotFromTheToken() throws Exception {
        String claimsEmployee =
                TestTokens.signed(
                        TestTokens.validClaims(employee, session, now)
                                .claim("role", "EMPLOYEE")
                                .build());

        MvcResult result = me(claimsEmployee);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result)).containsEntry("role", "SUPER_ADMIN");
    }

    @Test
    void theRequestLogCarriesTheEmployeeAndOrganizationIds(CapturedOutput output) throws Exception {
        me(TestTokens.signed(TestTokens.validClaims(employee, session, now).build()));

        String requestLine =
                output.getOut()
                        .lines()
                        .filter(line -> line.contains("HTTP request completed"))
                        .filter(line -> line.contains("/api/v1/me"))
                        .reduce((first, second) -> second)
                        .orElseThrow();
        assertThat(requestLine)
                .contains("\"actorId\":\"" + employee.id() + "\"")
                .contains("\"organizationId\":\"" + employee.organizationId() + "\"")
                .doesNotContain(employee.email());
    }

    // ---- no token, or not a bearer token ----

    @Test
    void noTokenIsRejected() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/me")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(result)).containsEntry("detail", ProblemAuthenticationEntryPoint.DETAIL);
    }

    @Test
    void garbageIsRejected() throws Exception {
        assertRejected("not-a-jwt");
        assertRejected("a.b.c");
    }

    @Test
    void aTokenInTheQueryStringIsIgnored() throws Exception {
        String token = TestTokens.signed(TestTokens.validClaims(employee, session, now).build());

        MvcResult result =
                mvc.perform(get("/api/v1/me").queryParam("access_token", token)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    // ---- forged or confused signatures ----

    @Test
    void aTokenSignedWithAnotherKeyIsRejectedEvenWithTheRightKid() throws Exception {
        assertRejected(
                TestTokens.es256(
                        TestJwtKeys.OTHER,
                        TestJwtKeys.KEY_ID,
                        TestTokens.validClaims(employee, session, now).build()));
    }

    @Test
    void anUnknownKidIsRejected() throws Exception {
        assertRejected(
                TestTokens.es256(
                        TestJwtKeys.SIGNING,
                        "some-other-kid",
                        TestTokens.validClaims(employee, session, now).build()));
    }

    @Test
    void anUnsignedTokenIsRejected() throws Exception {
        assertRejected(TestTokens.unsigned(TestTokens.validClaims(employee, session, now).build()));
    }

    @Test
    void theHs256AlgorithmConfusionAttackIsRejected() throws Exception {
        assertRejected(
                TestTokens.hs256WithPublicKey(
                        TestTokens.validClaims(employee, session, now).build()));
    }

    @Test
    void aTamperedPayloadIsRejected() throws Exception {
        String token = TestTokens.signed(TestTokens.validClaims(employee, session, now).build());
        String[] parts = token.split("\\.");
        String forgedPayload =
                java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                TestTokens.validClaims(employee, session, now)
                                        .claim("role", "ADMIN")
                                        .build()
                                        .toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertRejected(parts[0] + "." + forgedPayload + "." + parts[2]);
    }

    // ---- claims ----

    @Test
    void expiryIsEnforcedWithThirtySecondsOfSkew() throws Exception {
        JWTClaimsSet withinSkew =
                TestTokens.validClaims(employee, session, now.minusSeconds(900))
                        .expirationTime(Date.from(now.minusSeconds(10)))
                        .build();
        assertThat(me(TestTokens.signed(withinSkew)).getResponse().getStatus()).isEqualTo(200);

        assertRejected(
                TestTokens.signed(
                        TestTokens.validClaims(employee, session, now.minusSeconds(900))
                                .expirationTime(Date.from(now.minusSeconds(45)))
                                .build()));
    }

    @Test
    void aTokenIssuedInTheFutureIsRejected() throws Exception {
        assertRejected(
                TestTokens.signed(
                        TestTokens.validClaims(employee, session, now.plusSeconds(120)).build()));
    }

    @Test
    void theWrongIssuerOrAudienceIsRejected() throws Exception {
        assertRejected(
                TestTokens.signed(
                        TestTokens.validClaims(employee, session, now)
                                .issuer("someone-else")
                                .build()));
        assertRejected(
                TestTokens.signed(
                        TestTokens.validClaims(employee, session, now)
                                .audience(List.of("another-api"))
                                .build()));
    }

    @Test
    void everyRequiredClaimMustBePresent() throws Exception {
        for (String claim : new String[] {"sub", "org", "sid", "role", "jti", "iat", "exp"}) {
            JWTClaimsSet claims =
                    new JWTClaimsSet.Builder(TestTokens.validClaims(employee, session, now).build())
                            .claim(claim, null)
                            .build();
            assertRejected(TestTokens.signed(claims));
        }
    }

    @Test
    void identifiersThatAreNotUuidsAreRejected() throws Exception {
        assertRejected(
                TestTokens.signed(
                        TestTokens.validClaims(employee, session, now).subject("42").build()));
        assertRejected(
                TestTokens.signed(
                        TestTokens.validClaims(employee, session, now)
                                .claim("org", "acme")
                                .build()));
    }

    // ---- the database check (B2-3/14) ----

    @Test
    void aTokenForAnotherOrganizationThanTheEmployeesIsRejected() throws Exception {
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);

        assertRejected(
                TestTokens.signed(
                        TestTokens.validClaims(employee, session, now)
                                .claim("org", other.id().toString())
                                .build()));
    }

    @Test
    void aTokenForAnUnknownEmployeeOrSessionIsRejected() throws Exception {
        assertRejected(
                TestTokens.signed(
                        TestTokens.validClaims(employee, session, now)
                                .subject(UUID.randomUUID().toString())
                                .build()));
        assertRejected(
                TestTokens.signed(
                        TestTokens.validClaims(employee, UUID.randomUUID(), now).build()));
    }

    @Test
    void anotherEmployeesSessionCannotBeBorrowed() throws Exception {
        TestIdentities.Employee colleague =
                TestIdentities.activeEmployee(
                        jdbc,
                        new TestIdentities.Organization(
                                employee.organizationId(), employee.organizationLoginKey()),
                        "EMPLOYEE",
                        null);
        UUID colleagueSession = TestIdentities.activeSession(jdbc, colleague, now);

        assertRejected(
                TestTokens.signed(TestTokens.validClaims(employee, colleagueSession, now).build()));
    }

    @Test
    void aRevokedSessionIsRejectedImmediately() throws Exception {
        String token = TestTokens.signed(TestTokens.validClaims(employee, session, now).build());
        assertThat(me(token).getResponse().getStatus()).isEqualTo(200);

        jdbc.update(
                "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                        + " revoke_reason = 'LOGOUT' WHERE family_id = ?",
                session);

        assertRejected(token);
    }

    @Test
    void anExpiredSessionIsRejected() throws Exception {
        String token = TestTokens.signed(TestTokens.validClaims(employee, session, now).build());
        jdbc.update(
                "UPDATE refresh_token SET expires_at = now() - interval '1 second',"
                        + " absolute_expires_at = now() WHERE family_id = ?",
                session);

        assertRejected(token);
    }

    @Test
    void anInactiveEmployeeIsRejectedImmediately() throws Exception {
        String token = TestTokens.signed(TestTokens.validClaims(employee, session, now).build());
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", employee.id());

        assertRejected(token);
    }

    @Test
    void anInactiveOrganizationIsRejectedImmediately() throws Exception {
        String token = TestTokens.signed(TestTokens.validClaims(employee, session, now).build());
        jdbc.update(
                "UPDATE organization SET status = 'SUSPENDED' WHERE id = ?",
                employee.organizationId());

        assertRejected(token);
    }

    // ---- public endpoints ignore bearer tokens ----

    @Test
    void aBrokenTokenOnAPublicEndpointIsIgnored() throws Exception {
        MvcResult result =
                mvc.perform(
                                post("/api/v1/public/organizations/resend-verification")
                                        .header("Authorization", "Bearer not-a-jwt")
                                        .contentType("application/json")
                                        .content("{}"))
                        .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }
}
