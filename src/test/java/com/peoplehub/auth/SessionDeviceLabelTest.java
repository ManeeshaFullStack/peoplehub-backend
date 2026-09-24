package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.security.PasswordHasher;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestIdentities;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Login stores only the coarse device label, never the raw {@code User-Agent} or the IP, and every
 * refresh copies it to the successor token (b2-6, B2-6/3).
 */
@IntegrationTest
@AutoConfigureMockMvc
class SessionDeviceLabelTest {

    private static final String PASSWORD = "xk9$mQ2vTz8!wLp4Rb";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/128.0.0.0 Safari/537.36";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordHasher passwordHasher;

    private TestIdentities.Employee employee() {
        return TestIdentities.activeEmployee(
                jdbc,
                TestIdentities.activeOrganization(jdbc),
                "EMPLOYEE",
                passwordHasher.hash(PASSWORD));
    }

    private AuthTestClient.Session login(TestIdentities.Employee employee, String userAgent)
            throws Exception {
        var request =
                post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(
                                AuthTestClient.JSON.writeValueAsString(
                                        Map.of(
                                                "organization",
                                                employee.organizationLoginKey(),
                                                "email",
                                                employee.email(),
                                                "password",
                                                PASSWORD)));
        if (userAgent != null) {
            request = request.header("User-Agent", userAgent);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return AuthTestClient.session(result);
    }

    private List<String> labels(TestIdentities.Employee employee) {
        return jdbc.queryForList(
                "SELECT device_label FROM refresh_token WHERE employee_id = ? ORDER BY created_at",
                String.class,
                employee.id());
    }

    @Test
    void loginStoresOnlyTheCoarseLabel() throws Exception {
        TestIdentities.Employee employee = employee();

        login(employee, USER_AGENT);

        assertThat(labels(employee)).containsExactly("Chrome on Windows");
        // Nothing of the raw header or the address is stored anywhere in the row.
        String row =
                jdbc.queryForObject(
                        "SELECT row_to_json(rt)::text FROM refresh_token rt WHERE employee_id = ?",
                        String.class,
                        employee.id());
        assertThat(row)
                .doesNotContain("Mozilla")
                .doesNotContain("AppleWebKit")
                .doesNotContain("128.0")
                .doesNotContain("127.0.0.1");
    }

    @Test
    void aLoginWithoutAUserAgentIsAnUnknownDevice() throws Exception {
        TestIdentities.Employee employee = employee();

        login(employee, null);

        assertThat(labels(employee)).containsExactly(DeviceLabels.UNKNOWN);
    }

    @Test
    void everyRefreshKeepsTheLabelEvenFromAnotherBrowser() throws Exception {
        TestIdentities.Employee employee = employee();
        AuthTestClient client = new AuthTestClient(mvc);
        AuthTestClient.Session session = login(employee, USER_AGENT);

        for (int i = 0; i < 2; i++) {
            MvcResult refreshed =
                    mvc.perform(
                                    session.withCookies(post("/api/v1/auth/refresh"))
                                            .header("User-Agent", "Firefox/130.0 Linux"))
                            .andReturn();
            assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
            session = AuthTestClient.session(refreshed);
        }

        // The label belongs to the session, set at sign-in; a refresh never relabels it.
        assertThat(labels(employee)).hasSize(3).containsOnly("Chrome on Windows");
        assertThat(client.meStatus(session)).isEqualTo(200);
    }

    @Test
    void separateSignInsGetTheirOwnLabels() throws Exception {
        TestIdentities.Employee employee = employee();

        login(employee, USER_AGENT);
        login(
                employee,
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) Version/17.6 Safari/604.1");

        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT device_label FROM refresh_token"
                                        + " WHERE employee_id = ? ORDER BY 1",
                                String.class,
                                employee.id()))
                .containsExactly("Chrome on Windows", "Safari on iOS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT family_id) FROM refresh_token"
                                        + " WHERE employee_id = ?",
                                Integer.class,
                                employee.id()))
                .isEqualTo(2);
    }
}
