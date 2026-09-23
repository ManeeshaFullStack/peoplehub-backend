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
 *   <li>Login, refresh and logout document their own 401/403 answers.
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
                    Map.of("403", "The CSRF token or origin check failed."));

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
            item.readOperations().forEach(SecurityOpenApiCustomizer::requireToken);
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
