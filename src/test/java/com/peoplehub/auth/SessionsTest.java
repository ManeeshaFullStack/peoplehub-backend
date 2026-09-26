package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.SignedJWT;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;

/**
 * {@code GET /me/sessions}, {@code DELETE /me/sessions/{id}} and {@code POST
 * /me/sessions/revoke-others} end to end (b2-6, B2-6/1, 2, 4, 5, 15, 16): the caller's own active
 * sessions only, paginated and sortable; ending one (the current one signs this device out) or
 * every other one; audit rows; nothing secret in any response; and another employee's or
 * organization's session is "not found".
 */
@IntegrationTest
@AutoConfigureMockMvc
class SessionsTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String SESSIONS = "/api/v1/me/sessions";
    private static final String WINDOWS_CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/128.0.0.0 Safari/537.36";

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;

    private TestIdentities.Employee employee(TestIdentities.Organization org) {
        return TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", passwordHasher.hash(PASSWORD));
    }

    private TestIdentities.Employee employee() {
        return employee(TestIdentities.activeOrganization(jdbc));
    }

    private AuthTestClient.Session signIn(TestIdentities.Employee employee) throws Exception {
        MvcResult result =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .header("User-Agent", WINDOWS_CHROME)
                                        .contentType("application/json")
                                        .content(
                                                AuthTestClient.JSON.writeValueAsString(
                                                        Map.of(
                                                                "organization",
                                                                employee.organizationLoginKey(),
                                                                "email",
                                                                employee.email(),
                                                                "password",
                                                                PASSWORD))))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return AuthTestClient.session(result);
    }

    private static UUID sessionId(AuthTestClient.Session session) throws Exception {
        return UUID.fromString(
                SignedJWT.parse(session.accessToken()).getJWTClaimsSet().getStringClaim("sid"));
    }

    private static MockHttpServletRequestBuilder as(
            AuthTestClient.Session session, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + session.accessToken());
    }

    private MvcResult list(AuthTestClient.Session session, String query) throws Exception {
        return mvc.perform(as(session, get(SESSIONS + query))).andReturn();
    }

    private MvcResult revoke(AuthTestClient.Session session, UUID sessionId) throws Exception {
        return mvc.perform(as(session, delete(SESSIONS + "/" + sessionId))).andReturn();
    }

    private MvcResult revokeOthers(AuthTestClient.Session session) throws Exception {
        return mvc.perform(as(session, post(SESSIONS + "/revoke-others"))).andReturn();
    }

    private int meStatus(AuthTestClient.Session session) throws Exception {
        return new AuthTestClient(mvc).meStatus(session);
    }

    private static Map<String, Object> body(MvcResult result) throws Exception {
        return AuthTestClient.JSON.readValue(
                result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(MvcResult result) throws Exception {
        return (List<Map<String, Object>>) body(result).get("items");
    }

    private static List<String> ids(MvcResult result) throws Exception {
        return items(result).stream().map(item -> (String) item.get("sessionId")).toList();
    }

    private List<Map<String, Object>> audits(UUID organizationId, String action) {
        return jdbc.queryForList(
                "SELECT actor_id, target_type, target_id, host(ip) AS ip,"
                        + " details -> 'attributes' AS attributes"
                        + " FROM audit_log WHERE organization_id = ? AND action = ? ORDER BY id",
                organizationId,
                action);
    }

    private String revokeReason(UUID sessionId) {
        return jdbc.queryForObject(
                "SELECT string_agg(DISTINCT coalesce(revoke_reason, 'ACTIVE'), ',')"
                        + " FROM refresh_token WHERE family_id = ?",
                String.class,
                sessionId);
    }

    // ---- list ----

    @Test
    void theListShowsOnlyTheCallersActiveSessionsAndMarksTheCurrentOne() throws Exception {
        TestIdentities.Employee employee = employee();
        AuthTestClient.Session first = signIn(employee);
        AuthTestClient.Session second = signIn(employee);
        AuthTestClient.Session third = signIn(employee);
        signIn(employee(TestIdentities.activeOrganization(jdbc)));

        MvcResult result = list(second, "");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result))
                .containsOnlyKeys("items", "page", "size", "totalElements", "totalPages")
                .containsEntry("page", 0)
                .containsEntry("size", 20)
                .containsEntry("totalElements", 3)
                .containsEntry("totalPages", 1);
        assertThat(ids(result))
                .containsExactlyInAnyOrder(
                        sessionId(first).toString(),
                        sessionId(second).toString(),
                        sessionId(third).toString());
        for (Map<String, Object> item : items(result)) {
            assertThat(item)
                    .containsOnlyKeys(
                            "sessionId",
                            "current",
                            "deviceLabel",
                            "createdAt",
                            "lastUsedAt",
                            "expiresAt",
                            "absoluteExpiresAt")
                    .containsEntry("deviceLabel", "Chrome on Windows")
                    .containsEntry(
                            "current", item.get("sessionId").equals(sessionId(second).toString()));
        }
    }

    @Test
    void noTokenHashAddressOrRawHeaderAppearsAnywhereInTheResponse() throws Exception {
        TestIdentities.Employee employee = employee();
        AuthTestClient.Session session = signIn(employee);
        signIn(employee);
        List<String> hashes =
                jdbc.queryForList(
                        "SELECT token_hash FROM refresh_token WHERE employee_id = ?",
                        String.class,
                        employee.id());

        String response = list(session, "").getResponse().getContentAsString();

        assertThat(response)
                .doesNotContain(session.refreshToken())
                .doesNotContain(session.accessToken())
                .doesNotContain(session.csrfToken())
                .doesNotContain("127.0.0.1")
                .doesNotContain("Mozilla")
                .doesNotContain(employee.email())
                .doesNotContain(employee.organizationId().toString());
        for (String hash : hashes) {
            assertThat(response).doesNotContain(hash);
        }
    }

    @Test
    void endedAndExpiredSessionsAreNotListed() throws Exception {
        TestIdentities.Employee employee = employee();
        AuthTestClient.Session current = signIn(employee);
        AuthTestClient.Session loggedOut = signIn(employee);
        AuthTestClient.Session expired = signIn(employee);
        new AuthTestClient(mvc).logout(loggedOut);
        jdbc.update(
                "UPDATE refresh_token SET expires_at = now() - interval '1 minute'"
                        + " WHERE family_id = ?",
                sessionId(expired));

        assertThat(ids(list(current, ""))).containsExactly(sessionId(current).toString());
    }

    @Test
    void itPagesWithStableTotalsAndAPastTheEndPageIsEmpty() throws Exception {
        TestIdentities.Employee employee = employee();
        AuthTestClient.Session current = signIn(employee);
        for (int i = 0; i < 4; i++) {
            signIn(employee);
        }

        MvcResult first = list(current, "?size=2");
        MvcResult second = list(current, "?size=2&page=1");
        MvcResult last = list(current, "?size=2&page=2");
        MvcResult past = list(current, "?size=2&page=3");

        assertThat(body(first))
                .containsEntry("size", 2)
                .containsEntry("totalElements", 5)
                .containsEntry("totalPages", 3);
        assertThat(ids(first)).hasSize(2);
        assertThat(ids(second)).hasSize(2);
        assertThat(ids(last)).hasSize(1);
        assertThat(past.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(past)).isEmpty();
        List<String> all = new ArrayList<>();
        all.addAll(ids(first));
        all.addAll(ids(second));
        all.addAll(ids(last));
        assertThat(all).doesNotHaveDuplicates().hasSize(5);
    }

    @Test
    void theDefaultOrderIsMostRecentlyUsedFirstAndCreatedAtCanBeChosen() throws Exception {
        TestIdentities.Employee employee = employee();
        AuthTestClient.Session oldest = signIn(employee);
        AuthTestClient.Session middle = signIn(employee);
        AuthTestClient.Session newest = signIn(employee);
        // Refreshing the oldest session makes it the most recently used.
        MvcResult refreshed = new AuthTestClient(mvc).refresh(oldest);
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        AuthTestClient.Session oldestNow = AuthTestClient.session(refreshed);

        assertThat(ids(list(newest, "")))
                .containsExactly(
                        sessionId(oldest).toString(),
                        sessionId(newest).toString(),
                        sessionId(middle).toString());
        assertThat(ids(list(newest, "?sort=createdAt,asc")))
                .containsExactly(
                        sessionId(oldest).toString(),
                        sessionId(middle).toString(),
                        sessionId(newest).toString());
        assertThat(ids(list(newest, "?sort=createdAt,desc")))
                .containsExactly(
                        sessionId(newest).toString(),
                        sessionId(middle).toString(),
                        sessionId(oldest).toString());
        assertThat(sessionId(oldestNow)).isEqualTo(sessionId(oldest));
    }

    @Test
    void anUnknownSortOrAnOversizedPageIsRejected() throws Exception {
        TestIdentities.Employee employee = employee();
        AuthTestClient.Session session = signIn(employee);

        for (String query :
                List.of(
                        "?sort=deviceLabel,asc",
                        "?sort=family_id,asc",
                        "?sort=createdAt,sideways",
                        "?size=101",
                        "?size=0",
                        "?page=-1")) {
            MvcResult result = list(session, query);
            assertThat(result.getResponse().getStatus()).as(query).isEqualTo(400);
            assertThat(body(result))
                    .as(query)
                    .containsEntry("type", "urn:peoplehub:problem:invalid-page-request");
        }
        assertThat(body(list(session, "?sort=deviceLabel,asc")).get("allowedSortFields"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("createdAt", "lastUsedAt");
        assertThat(list(session, "?size=100").getResponse().getStatus()).isEqualTo(200);
    }

    // ---- revoke one ----

    @Test
    void endingTheCurrentSessionSignsThisDeviceOut() throws Exception {
        TestIdentities.Employee employee = employee();
        AuthTestClient.Session current = signIn(employee);
        AuthTestClient.Session other = signIn(employee);

        MvcResult result = revoke(current, sessionId(current));

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .hasSize(2)
                .allSatisfy(cookie -> assertThat(cookie).contains("Max-Age=0"));
        assertThat(meStatus(current)).isEqualTo(401);
        assertThat(new AuthTestClient(mvc).refresh(current).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(revokeReason(sessionId(current))).isEqualTo("SESSION_REVOKED");
        assertThat(meStatus(other)).isEqualTo(200);

        List<Map<String, Object>> audits = audits(employee.organizationId(), "SESSION_REVOKED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .containsEntry("actor_id", employee.id().toString())
                .containsEntry("target_type", "EMPLOYEE")
                .containsEntry("target_id", employee.id().toString())
                .containsEntry("ip", "127.0.0.1");
        assertThat(audits.get(0).get("attributes").toString())
                .contains(sessionId(current).toString());
    }

    @Test
    void endingAnotherOwnSessionLeavesThisOneSignedIn() throws Exception {
        TestIdentities.Employee employee = employee();
        AuthTestClient.Session current = signIn(employee);
        AuthTestClient.Session other = signIn(employee);

        MvcResult result = revoke(current, sessionId(other));

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(meStatus(current)).isEqualTo(200);
        assertThat(meStatus(other)).isEqualTo(401);
        assertThat(revokeReason(sessionId(other))).isEqualTo("SESSION_REVOKED");
        assertThat(revokeReason(sessionId(current))).isEqualTo("ACTIVE");
        assertThat(ids(list(current, ""))).containsExactly(sessionId(current).toString());
    }

    @Test
    void anySessionThatIsNotOneOfTheCallersActiveSessionsIsTheSame404() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee employee = employee(org);
        AuthTestClient.Session current = signIn(employee);
        AuthTestClient.Session colleague = signIn(employee(org));
        AuthTestClient.Session foreign = signIn(employee());
        AuthTestClient.Session loggedOut = signIn(employee);
        new AuthTestClient(mvc).logout(loggedOut);
        AuthTestClient.Session expired = signIn(employee);
        jdbc.update(
                "UPDATE refresh_token SET expires_at = now() - interval '1 minute'"
                        + " WHERE family_id = ?",
                sessionId(expired));

        Map<String, UUID> targets = new LinkedHashMap<>();
        targets.put("unknown", UUID.randomUUID());
        targets.put("another employee's", sessionId(colleague));
        targets.put("another organization's", sessionId(foreign));
        targets.put("already ended", sessionId(loggedOut));
        targets.put("expired", sessionId(expired));

        Map<String, Object> first = null;
        for (Map.Entry<String, UUID> target : targets.entrySet()) {
            MvcResult result = revoke(current, target.getValue());
            assertThat(result.getResponse().getStatus()).as(target.getKey()).isEqualTo(404);
            Map<String, Object> body = body(result);
            body.remove("instance");
            body.remove("correlationId");
            if (first == null) {
                first = body;
            }
            assertThat(body).as(target.getKey()).isEqualTo(first);
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(target.getValue().toString());
        }
        assertThat(first)
                .containsEntry("type", "urn:peoplehub:problem:not-found")
                .containsEntry("detail", SessionService.NOT_FOUND);

        assertThat(meStatus(colleague)).isEqualTo(200);
        assertThat(meStatus(foreign)).isEqualTo(200);
        assertThat(revokeReason(sessionId(expired))).isEqualTo("ACTIVE");
        assertThat(audits(employee.organizationId(), "SESSION_REVOKED")).isEmpty();
    }

    @Test
    void aSessionIdThatIsNotAUuidIsABadRequest() throws Exception {
        AuthTestClient.Session session = signIn(employee());

        MvcResult result =
                mvc.perform(as(session, delete(SESSIONS + "/not-a-session"))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("not-a-session");
    }

    // ---- revoke others ----

    @Test
    void revokeOthersKeepsThisSessionAndEndsEveryOtherOne() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee employee = employee(org);
        AuthTestClient.Session current = signIn(employee);
        AuthTestClient.Session second = signIn(employee);
        AuthTestClient.Session third = signIn(employee);
        AuthTestClient.Session colleague = signIn(employee(org));

        MvcResult result = revokeOthers(current);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(meStatus(current)).isEqualTo(200);
        assertThat(meStatus(second)).isEqualTo(401);
        assertThat(meStatus(third)).isEqualTo(401);
        assertThat(meStatus(colleague)).isEqualTo(200);
        assertThat(revokeReason(sessionId(second))).isEqualTo("SESSION_REVOKED");
        assertThat(revokeReason(sessionId(third))).isEqualTo("SESSION_REVOKED");
        assertThat(ids(list(current, ""))).containsExactly(sessionId(current).toString());
        // The current session can still be refreshed.
        assertThat(new AuthTestClient(mvc).refresh(current).getResponse().getStatus())
                .isEqualTo(200);

        List<Map<String, Object>> audits =
                audits(employee.organizationId(), "OTHER_SESSIONS_REVOKED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .containsEntry("actor_id", employee.id().toString())
                .containsEntry("target_id", employee.id().toString());
        assertThat(audits.get(0).get("attributes").toString())
                .isEqualTo("{\"revokedSessions\": 2}");
    }

    @Test
    void revokeOthersWithNothingElseOpenStillSucceedsAndRecordsZero() throws Exception {
        TestIdentities.Employee employee = employee();
        AuthTestClient.Session only = signIn(employee);

        assertThat(revokeOthers(only).getResponse().getStatus()).isEqualTo(204);

        assertThat(meStatus(only)).isEqualTo(200);
        assertThat(audits(employee.organizationId(), "OTHER_SESSIONS_REVOKED").get(0))
                .extracting(row -> row.get("attributes").toString())
                .isEqualTo("{\"revokedSessions\": 0}");
    }

    // ---- authentication ----

    @Test
    void everySessionEndpointNeedsAnAccessToken() throws Exception {
        for (MockHttpServletRequestBuilder request :
                List.of(
                        get(SESSIONS),
                        delete(SESSIONS + "/" + UUID.randomUUID()),
                        post(SESSIONS + "/revoke-others"))) {
            MvcResult result = mvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(result)).containsEntry("type", "urn:peoplehub:problem:unauthorized");
        }
    }
}
