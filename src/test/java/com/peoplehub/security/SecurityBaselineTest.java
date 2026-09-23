package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The deny-by-default security chain (b2-3, B2-3 implementation plan, B2-3/16).
 *
 * <p>{@link #everyNonPublicEndpointRejectsAnUnauthenticatedCall} is a fail-closed inventory: it
 * walks every mapped controller method, so a new endpoint is checked automatically, and it fails if
 * one is reachable without authentication without being listed in {@link PublicEndpoints}.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SecurityBaselineTest {

    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyNonPublicEndpointRejectsAnUnauthenticatedCall() throws Exception {
        List<String> checked = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                String path = concrete(pattern);
                if (isPublic(path)) {
                    continue;
                }
                for (RequestMethod method : methodsOf(info)) {
                    MvcResult result =
                            mvc.perform(request(HttpMethod.valueOf(method.name()), path))
                                    .andReturn();
                    assertThat(result.getResponse().getStatus())
                            .as(method + " " + pattern)
                            .isEqualTo(401);
                    checked.add(method + " " + pattern);
                }
            }
        }
        // Guards against the inventory silently walking nothing.
        assertThat(handlerMapping.getHandlerMethods()).isNotEmpty();
        // The default itself: a path no controller maps is denied too, not a 404 that would reveal
        // which routes exist.
        mvc.perform(get("/api/v1/definitely/not/mapped")).andExpect(status().isUnauthorized());
        assertThat(checked)
                .contains("GET /api/v1/me")
                .doesNotContain("POST /api/v1/public/organizations/register");
    }

    @Test
    void theUnauthorizedResponseIsTheStandardProblemBodyAndNothingMore() throws Exception {
        MvcResult result =
                mvc.perform(get("/api/v1/definitely/not/mapped"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string("WWW-Authenticate", "Bearer"))
                        .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        Map<String, Object> body =
                JSON.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
        String correlationId = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(body)
                .containsOnlyKeys("type", "title", "status", "detail", "instance", "correlationId")
                .containsEntry("type", "urn:peoplehub:problem:unauthorized")
                .containsEntry("title", "Authentication failed")
                .containsEntry("status", 401)
                .containsEntry("detail", ProblemAuthenticationEntryPoint.DETAIL)
                .containsEntry("instance", "urn:peoplehub:request:" + correlationId)
                .containsEntry("correlationId", correlationId);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("definitely");
    }

    @Test
    void publicEndpointsDoNotRequireAuthentication() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        // Reaches the controller: an empty body is a validation error, not a 401.
        mvc.perform(
                        post("/api/v1/public/organizations/resend-verification")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest());
        // Reaches the controller, which rejects the missing signature itself.
        mvc.perform(
                        post("/api/v1/webhooks/email/events")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void thereIsNoDefaultUserAndNoSession() throws Exception {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();

        MvcResult result = mvc.perform(get("/api/v1/definitely/not/mapped")).andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void crossOriginRequestsAreNotAllowedWhenNoAppOriginIsConfigured() throws Exception {
        MvcResult result =
                mvc.perform(
                                request(HttpMethod.OPTIONS, "/api/v1/auth/login")
                                        .header("Origin", "https://evil.example")
                                        .header("Access-Control-Request-Method", "POST"))
                        .andReturn();
        // No CORS headers at all, so the browser refuses. (With an app origin configured, a foreign
        // origin is actively rejected with a 403 problem body: LoginTest.)
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    private static boolean isPublic(String path) {
        MockHttpServletRequest probe = new MockHttpServletRequest("GET", path);
        return PublicEndpoints.matcher().matches(probe);
    }

    private static List<RequestMethod> methodsOf(RequestMappingInfo info) {
        var methods = info.getMethodsCondition().getMethods();
        return methods.isEmpty() ? List.of(RequestMethod.GET) : List.copyOf(methods);
    }

    private static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
    }
}
