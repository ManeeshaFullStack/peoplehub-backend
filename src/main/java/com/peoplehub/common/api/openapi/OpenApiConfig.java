package com.peoplehub.common.api.openapi;

import com.peoplehub.common.api.paging.PageParams;
import com.peoplehub.common.api.paging.PageQuery;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.math.BigDecimal;
import java.util.Arrays;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;

/**
 * OpenAPI generation (Spec 2, 13): API info, the shared {@code Problem} schema, the standard error
 * responses on every operation, and documentation of the {@code page}/{@code size}/{@code sort}
 * parameters of endpoints that take a {@link PageQuery}.
 *
 * <p>Error responses are documented as 400 (only for operations that take input) and 500. The
 * bearer scheme and the 401/403 responses are added by {@code security.SecurityOpenApiCustomizer}
 * (b2-3), from the same list of public endpoints the security chain uses.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String PROBLEM_SCHEMA = "Problem";
    private static final String PROBLEM_JSON = "application/problem+json";

    static {
        // PageQuery is bound by our own resolver; without this springdoc would expand it into
        // query parameters named after its record components.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(PageQuery.class);
    }

    @Bean
    public OpenAPI peopleHubOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        String version =
                buildProperties.stream().map(BuildProperties::getVersion).findFirst().orElse("dev");
        return new OpenAPI()
                .info(
                        new Info()
                                .title("PeopleHub API")
                                .version(version)
                                .description(
                                        "HR operations API: attendance, leave, approvals and organisation "
                                                + "management. Errors use RFC 9457 problem details."));
    }

    @Bean
    public OpenApiCustomizer problemSchemaCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
        };
    }

    @Bean
    public OperationCustomizer standardOperationCustomizer() {
        return (operation, handlerMethod) -> {
            for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
                if (PageQuery.class.equals(parameter.getParameterType())) {
                    addPagingParameters(
                            operation, parameter.getParameterAnnotation(PageParams.class));
                }
            }
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }
            boolean takesInput =
                    operation.getRequestBody() != null
                            || (operation.getParameters() != null
                                    && !operation.getParameters().isEmpty());
            if (takesInput) {
                responses.putIfAbsent("400", problemResponse("Invalid request (see fieldErrors)."));
            }
            responses.putIfAbsent("500", problemResponse("Unexpected error."));
            return operation;
        };
    }

    private static void addPagingParameters(Operation operation, PageParams params) {
        int defaultSize = params != null ? params.defaultSize() : PageQuery.DEFAULT_SIZE;
        String[] sortable = params != null ? params.sortable() : new String[0];
        String defaultSort = params != null ? params.defaultSort() : "";

        operation.addParametersItem(
                new Parameter()
                        .in("query")
                        .name("page")
                        .required(false)
                        .description("Zero-based page index.")
                        .schema(new IntegerSchema()._default(0).minimum(BigDecimal.ZERO)));
        operation.addParametersItem(
                new Parameter()
                        .in("query")
                        .name("size")
                        .required(false)
                        .description(
                                "Page size. Values above " + PageQuery.MAX_SIZE + " are rejected.")
                        .schema(
                                new IntegerSchema()
                                        ._default(defaultSize)
                                        .minimum(BigDecimal.ONE)
                                        .maximum(BigDecimal.valueOf(PageQuery.MAX_SIZE))));

        StringBuilder sortDescription =
                new StringBuilder("Repeatable, as `field` or `field,asc|desc`. ");
        sortDescription.append(
                sortable.length == 0
                        ? "This endpoint is not sortable."
                        : "Sortable fields: " + String.join(", ", Arrays.asList(sortable)) + ".");
        if (!defaultSort.isBlank()) {
            sortDescription.append(" Default: `").append(defaultSort).append("`.");
        }
        operation.addParametersItem(
                new Parameter()
                        .in("query")
                        .name("sort")
                        .required(false)
                        .style(Parameter.StyleEnum.FORM)
                        .explode(true)
                        .description(sortDescription.toString())
                        .schema(new ArraySchema().items(new StringSchema())));
    }

    private static ApiResponse problemResponse(String description) {
        Schema<?> reference = new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA);
        return new ApiResponse()
                .description(description)
                .content(
                        new Content()
                                .addMediaType(PROBLEM_JSON, new MediaType().schema(reference)));
    }

    /**
     * Hand-written so the documented shape is exactly the contract, not whatever Jackson infers.
     */
    private static Schema<?> problemSchema() {
        Schema<?> fieldError =
                new ObjectSchema()
                        .addProperty(
                                "field",
                                new StringSchema().description("The request field at fault."))
                        .addProperty(
                                "message", new StringSchema().description("Why it was rejected."))
                        .addRequiredItem("field")
                        .addRequiredItem("message");
        return new ObjectSchema()
                .description("Problem details (RFC 9457) returned for every error response.")
                .addProperty(
                        "type",
                        new StringSchema()
                                .format("uri")
                                .example("urn:peoplehub:problem:validation-error"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema().format("int32"))
                .addProperty("detail", new StringSchema())
                .addProperty(
                        "instance",
                        new StringSchema()
                                .format("uri")
                                .description(
                                        "URN identifying this occurrence, built from the correlation id. It never "
                                                + "contains the request path or query string."))
                .addProperty(
                        "correlationId",
                        new StringSchema()
                                .description("Also returned in the X-Correlation-Id header."))
                .addProperty("fieldErrors", new ArraySchema().items(fieldError))
                .addProperty(
                        "allowedSortFields",
                        new ArraySchema()
                                .items(new StringSchema())
                                .description("Present when the sort parameter was rejected."))
                .addRequiredItem("type")
                .addRequiredItem("title")
                .addRequiredItem("status");
    }
}
