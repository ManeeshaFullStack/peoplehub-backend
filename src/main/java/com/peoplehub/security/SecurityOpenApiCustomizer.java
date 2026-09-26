package com.peoplehub.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Documents authentication in the OpenAPI document (b2-3; Spec 13: every endpoint documented).
 *
 * <ul>
 *   <li>A {@value #SCHEME} scheme: an ES256 JWT in the {@code Authorization} header.
 *   <li>Every operation that is not a {@link PublicEndpoints public endpoint} requires it and can
 *       answer 401 (and 403).
 *   <li>Login, refresh, logout, forgot password and reset password document their own 401/403
 *       answers.
 *   <li>The sessions and deactivation endpoints (b2-6) and MFA enrollment (b2-7) also document
 *       their 400/403/404/409 answers.
 * </ul>
 *
 * Driven by the same list as the security chain, so the document cannot say an endpoint is public
 * when the chain protects it, or the other way round.
 */
@Component
class SecurityOpenApiCustomizer implements OpenApiCustomizer {

    static final String SCHEME = "bearerAuth";

    private static final String PROBLEM_REF = "#/components/schemas/Problem";
    private static final String PROBLEM_JSON = "application/problem+json";

    private static final Map<String, Map<String, String>> AUTH_ENDPOINT_RESPONSES =
            Map.of(
                    "/api/v1/auth/login",
                    Map.of(
                            "401", "The organization, email or password is wrong.",
                            "403", "The request came from a foreign origin."),
                    "/api/v1/auth/refresh",
                    Map.of(
                            "401", "The session has ended; sign in again.",
                            "403", "The CSRF token or origin check failed."),
                    "/api/v1/auth/logout",
                    Map.of("403", "The CSRF token or origin check failed."),
                    "/api/v1/auth/forgot-password",
                    Map.of("403", "The request came from a foreign origin."),
                    "/api/v1/auth/reset-password",
                    Map.of("403", "The request came from a foreign origin."),
                    "/api/v1/auth/mfa/challenge",
                    mfaStepResponses(),
                    "/api/v1/auth/mfa/enroll",
                    Map.of(
                            "401", "The challenge cannot be used; sign in again.",
                            "403", "The request came from a foreign origin."),
                    "/api/v1/auth/mfa/enroll/confirm",
                    mfaStepResponses());

    private static Map<String, String> mfaStepResponses() {
        return Map.of(
                "400", "The code is missing, malformed or incorrect; it may be tried again.",
                "401", "The challenge cannot be used (any more); sign in again.",
                "403", "The request came from a foreign origin.");
    }

    /**
     * The other problem answers of authenticated operations whose failures are part of their
     * contract (b2-6): every operation under the path gets them, next to the 401/403 above.
     */
    private static final Map<String, Map<String, String>> PROBLEM_RESPONSES =
            Map.of(
                    "/api/v1/me/sessions",
                    Map.of("400", "The page, size or sort is invalid."),
                    "/api/v1/me/sessions/{sessionId}",
                    Map.of("404", "Not one of the caller's active sessions."),
                    "/api/v1/admin/employees/{id}/deactivate",
                    Map.of(
                            "400", "The exit date is malformed or later than today.",
                            "403", "The caller may not deactivate this employee.",
                            "404", "No such employee in the caller's organization.",
                            "409", "The employee cannot be deactivated in their current state."),
                    "/api/v1/admin/employees/{id}/reactivate",
                    Map.of(
                            "403", "The caller may not reactivate this employee.",
                            "404", "No such employee in the caller's organization.",
                            "409", "Only a deactivated employee can be reactivated."),
                    "/api/v1/me/mfa/enroll",
                    Map.of(
                            "409",
                            "The organization does not offer MFA, or the caller already has it."),
                    "/api/v1/me/mfa/confirm",
                    Map.of(
                            "400",
                            "The code is missing, malformed or incorrect.",
                            "409",
                            "Nothing to confirm: MFA is not offered, already enabled, or not"
                                    + " started."));

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents()
                .addSecuritySchemes(
                        SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description(
                                        "ES256 access token from POST /api/v1/auth/login or"
                                                + " /api/v1/auth/refresh. Valid for 15 minutes."));
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach(SecurityOpenApiCustomizer::document);
    }

    private static void document(String path, PathItem item) {
        if (!PublicEndpoints.isPublicPath(path)) {
            Map<String, String> problems = PROBLEM_RESPONSES.getOrDefault(path, Map.of());
            for (Operation operation : item.readOperations()) {
                requireToken(operation);
                problems.forEach(
                        (status, description) ->
                                responses(operation).put(status, problem(description)));
            }
            return;
        }
        Map<String, String> documented = AUTH_ENDPOINT_RESPONSES.getOrDefault(path, Map.of());
        for (Operation operation : item.readOperations()) {
            documented.forEach(
                    (status, description) ->
                            responses(operation).put(status, problem(description)));
        }
    }

    private static void requireToken(Operation operation) {
        operation.addSecurityItem(new SecurityRequirement().addList(SCHEME));
        responses(operation)
                .putIfAbsent("401", problem("Missing, invalid or expired access token."));
        responses(operation).putIfAbsent("403", problem("Authenticated, but not allowed."));
    }

    private static ApiResponses responses(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        return operation.getResponses();
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse()
                .description(description)
                .content(
                        new Content()
                                .addMediaType(
                                        PROBLEM_JSON,
                                        new MediaType().schema(new Schema<>().$ref(PROBLEM_REF))));
    }
}
