package com.peoplehub.security.principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.common.database.TenantBinding;
import com.peoplehub.common.database.TenantContext;
import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestIdentities;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * An authenticated request binds its principal's organization to every transaction it begins, the
 * per-request status check included, and nothing else (b2-8, O1, O2; B2-3/14, B2-3/20). Recorded
 * per thread, so the scheduled outbox job running meanwhile on its own thread cannot interfere.
 */
@IntegrationTest
@AutoConfigureMockMvc
class TenantRequestBindingTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessTokenIssuer issuer;
    @MockitoSpyBean private TenantBinding tenantBinding;

    /** Organizations bound on the test thread. */
    private final List<UUID> bound = new CopyOnWriteArrayList<>();

    @BeforeEach
    void recordBindingsOnThisThread() {
        Thread testThread = Thread.currentThread();
        doAnswer(
                        invocation -> {
                            if (Thread.currentThread() == testThread) {
                                bound.add(invocation.getArgument(1, UUID.class));
                            }
                            return invocation.callRealMethod();
                        })
                .when(tenantBinding)
                .applyTo(any(Connection.class), any(UUID.class));
    }

    @Test
    void anAuthenticatedRequestBindsOnlyItsOwnOrganization() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        TestIdentities.Organization other = TestIdentities.activeOrganization(jdbc);
        TestIdentities.activeEmployee(jdbc, other, "EMPLOYEE", null);

        int status =
                mvc.perform(get("/api/v1/me").header("Authorization", bearer(employee)))
                        .andReturn()
                        .getResponse()
                        .getStatus();

        assertThat(status).isEqualTo(200);
        // The status check (its own read-only transaction) and GET /me's transaction.
        assertThat(bound).hasSizeGreaterThanOrEqualTo(2).containsOnly(org.id());
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aTokenForAnotherOrganizationBindsThatOrganization() throws Exception {
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee employee = TestIdentities.activeEmployee(jdbc, org, "ADMIN", null);

        mvc.perform(get("/api/v1/me").header("Authorization", bearer(employee))).andReturn();

        assertThat(bound).isNotEmpty().containsOnly(org.id());
    }

    @Test
    void aRejectedTokenStillBindsOnlyTheOrganizationItClaims() throws Exception {
        // A deactivated employee's token: the status check runs (bound to the token's
        // organization), finds nothing, and the request is refused before any other transaction.
        TestIdentities.Organization org = TestIdentities.activeOrganization(jdbc);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(jdbc, org, "EMPLOYEE", null);
        String token = bearer(employee);
        jdbc.update("UPDATE employee SET status = 'DEACTIVATED' WHERE id = ?", employee.id());

        int status =
                mvc.perform(get("/api/v1/me").header("Authorization", token))
                        .andReturn()
                        .getResponse()
                        .getStatus();

        assertThat(status).isEqualTo(401);
        assertThat(bound).containsOnly(org.id()).hasSize(1);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aPublicRequestBindsNoOrganization() throws Exception {
        int status =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType("application/json")
                                        .content(
                                                JsonMapper.builder()
                                                        .build()
                                                        .writeValueAsString(
                                                                Map.of(
                                                                        "organization",
                                                                        "org-" + UUID.randomUUID(),
                                                                        "email",
                                                                        "nobody@example.com",
                                                                        "password",
                                                                        "not-the-password-123"))))
                        .andReturn()
                        .getResponse()
                        .getStatus();

        assertThat(status).isEqualTo(401);
        assertThat(bound).isEmpty();
        assertThat(TenantContext.current()).isEmpty();
    }

    private String bearer(TestIdentities.Employee caller) {
        UUID session = TestIdentities.activeSession(jdbc, caller, Instant.now());
        return "Bearer "
                + issuer.issue(caller.id(), caller.organizationId(), caller.role(), session)
                        .value();
    }
}
