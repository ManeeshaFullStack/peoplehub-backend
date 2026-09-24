package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The OpenAPI document describes authentication (b2-3): the bearer scheme, which operations need
 * it, and the 401/403 answers, all derived from {@link PublicEndpoints}.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SecurityOpenApiTest {

    private static final String PROBLEM_REF = "#/components/schemas/Problem";

    @Autowired private MockMvc mvc;

    @Test
    void theBearerSchemeIsDocumented() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat")
                                .value("JWT"));
    }

    @Test
    void anAuthenticatedOperationRequiresTheTokenAndDocumentsItsRejections() throws Exception {
        String me = "$.paths['/api/v1/me'].get";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(me + ".security[0]", hasKey("bearerAuth")))
                .andExpect(
                        jsonPath(
                                        me
                                                + ".responses['401'].content['application/problem+json'].schema['$ref']")
                                .value(PROBLEM_REF))
                .andExpect(jsonPath(me + ".responses['403']").exists());
    }

    @Test
    void publicOperationsNeedNoTokenAndTheAuthEndpointsDocumentTheirOwnFailures() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['403']").exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/refresh'].post.responses['401']").exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/refresh'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.security").doesNotExist())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/logout'].post.responses['403']").exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/public/organizations/register'].post.security")
                                .doesNotExist())
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/public/organizations/register'].post.responses",
                                not(hasKey("401"))));
    }

    @Test
    void forgotAndResetPasswordArePublicAndChangePasswordNeedsTheToken() throws Exception {
        for (String path : new String[] {"forgot-password", "reset-password"}) {
            String operation = "$.paths['/api/v1/auth/" + path + "'].post";
            mvc.perform(get("/v3/api-docs"))
                    .andExpect(jsonPath(operation + ".security").doesNotExist())
                    .andExpect(jsonPath(operation + ".responses", not(hasKey("401"))))
                    .andExpect(
                            jsonPath(
                                            operation
                                                    + ".responses['403'].content['application/problem+json'].schema['$ref']")
                                    .value(PROBLEM_REF));
        }
        mvc.perform(get("/v3/api-docs"))
                .andExpect(
                        jsonPath(
                                "$.paths['/api/v1/me/password'].post.security[0]",
                                hasKey("bearerAuth")))
                .andExpect(
                        jsonPath("$.paths['/api/v1/me/password'].post.responses['401']").exists());
    }

    @Test
    void theSessionAndDeactivationEndpointsNeedTheTokenAndDocumentTheirProblems() throws Exception {
        String sessions = "$.paths['/api/v1/me/sessions']";
        String session = "$.paths['/api/v1/me/sessions/{sessionId}']";
        String others = "$.paths['/api/v1/me/sessions/revoke-others']";
        String deactivate = "$.paths['/api/v1/admin/employees/{id}/deactivate']";
        String reactivate = "$.paths['/api/v1/admin/employees/{id}/reactivate']";
        var docs = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());

        for (String operation :
                new String[] {
                    sessions + ".get",
                    session + ".delete",
                    others + ".post",
                    deactivate + ".post",
                    reactivate + ".post"
                }) {
            docs.andExpect(jsonPath(operation + ".security[0]", hasKey("bearerAuth")))
                    .andExpect(
                            jsonPath(
                                            operation
                                                    + ".responses['401'].content['application/problem+json'].schema['$ref']")
                                    .value(PROBLEM_REF));
        }
        docs.andExpect(jsonPath(sessions + ".get.responses['400']").exists())
                .andExpect(jsonPath(sessions + ".get.responses['200']").exists())
                .andExpect(jsonPath(session + ".delete.responses['404']").exists())
                .andExpect(jsonPath(session + ".delete.responses['204']").exists())
                .andExpect(jsonPath(others + ".post.responses['204']").exists())
                .andExpect(jsonPath(deactivate + ".post.responses['204']").exists())
                .andExpect(jsonPath(deactivate + ".post.responses", hasKey("400")))
                .andExpect(jsonPath(deactivate + ".post.responses", hasKey("403")))
                .andExpect(jsonPath(deactivate + ".post.responses", hasKey("404")))
                .andExpect(jsonPath(deactivate + ".post.responses", hasKey("409")))
                .andExpect(jsonPath(reactivate + ".post.responses", hasKey("404")))
                .andExpect(jsonPath(reactivate + ".post.responses", hasKey("409")))
                .andExpect(
                        jsonPath(
                                        deactivate
                                                + ".post.responses['409'].content['application/problem+json'].schema['$ref']")
                                .value(PROBLEM_REF));
    }

    @Test
    void thePathCheckAgreesWithTheSecurityChain() {
        assertThat(PublicEndpoints.isPublicPath("/api/v1/auth/login")).isTrue();
        assertThat(PublicEndpoints.isPublicPath("/api/v1/public/organizations/register")).isTrue();
        assertThat(PublicEndpoints.isPublicPath("/actuator/health/liveness")).isTrue();
        assertThat(PublicEndpoints.isPublicPath("/api/v1/me")).isFalse();
        assertThat(PublicEndpoints.isPublicPath("/api/v1/me/sessions")).isFalse();
        assertThat(PublicEndpoints.isPublicPath("/api/v1/me/sessions/revoke-others")).isFalse();
        assertThat(PublicEndpoints.isPublicPath("/api/v1/admin/employees/x/deactivate")).isFalse();
        assertThat(PublicEndpoints.isPublicPath("/api/v1/auth/login/extra")).isFalse();
        assertThat(PublicEndpoints.isPublicPath("/actuator/metrics")).isFalse();
    }
}
