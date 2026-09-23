package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.peoplehub.support.TestIdentities;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives login, refresh and logout the way the frontend will (b2-3): the access token in memory,
 * the refresh token in its cookie, and the CSRF token from the response body echoed in {@code
 * X-CSRF-Token} next to its own cookie.
 */
final class AuthTestClient {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;

    AuthTestClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    /** What a client holds after a login or a refresh. */
    record Session(String accessToken, String refreshToken, String csrfToken) {

        MockHttpServletRequestBuilder withCookies(MockHttpServletRequestBuilder request) {
            return request.cookie(
                            new Cookie(AuthCookies.REFRESH_COOKIE, refreshToken),
                            new Cookie(AuthCookies.CSRF_COOKIE, csrfToken))
                    .header(CsrfOriginGuard.CSRF_HEADER, csrfToken);
        }
    }

    Session login(TestIdentities.Employee employee, String password) throws Exception {
        MvcResult result =
                mvc.perform(
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
                                                                password))))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).as("login").isEqualTo(200);
        return session(result);
    }

    MvcResult refresh(Session session) throws Exception {
        return mvc.perform(session.withCookies(post("/api/v1/auth/refresh"))).andReturn();
    }

    MvcResult logout(Session session) throws Exception {
        return mvc.perform(session.withCookies(post("/api/v1/auth/logout"))).andReturn();
    }

    int meStatus(Session session) throws Exception {
        return mvc.perform(
                        get("/api/v1/me")
                                .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    static Session session(MvcResult result) throws Exception {
        Map<String, Object> body = body(result);
        return new Session(
                (String) body.get("accessToken"),
                result.getResponse().getCookie(AuthCookies.REFRESH_COOKIE).getValue(),
                (String) body.get("csrfToken"));
    }

    static Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }
}
