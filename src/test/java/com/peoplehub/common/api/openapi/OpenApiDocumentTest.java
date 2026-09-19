package com.peoplehub.common.api.openapi;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The generated OpenAPI document (Spec 2, 13). Uses the full application context, with the sample
 * controller enabled through the {@code api-test} profile so there is something to document.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ActiveProfiles("api-test")
class OpenApiDocumentTest {

    private static final String LIST = "$.paths['/api/v1/api-test/items'].get";
    private static final String CREATE = "$.paths['/api/v1/api-test/items'].post";
    private static final String BOOM = "$.paths['/api/v1/api-test/boom'].get";

    @Autowired private MockMvc mvc;

    @Test
    void documentIsServedWithApiInfo() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("PeopleHub API"))
                .andExpect(jsonPath("$.info.version").isNotEmpty());
    }

    @Test
    void sharedProblemSchemaMatchesTheErrorContract() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(
                        jsonPath("$.components.schemas.Problem.required")
                                .value(hasItems("type", "title", "status")))
                .andExpect(jsonPath("$.components.schemas.Problem.properties.fieldErrors").exists())
                .andExpect(
                        jsonPath("$.components.schemas.Problem.properties.allowedSortFields")
                                .exists())
                .andExpect(
                        jsonPath("$.components.schemas.Problem.properties.correlationId").exists());
    }

    @Test
    void listEndpointDocumentsExactlyThePagingParametersWithTheirLimits() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                // PageQuery must not be expanded into its record components.
                .andExpect(
                        jsonPath(LIST + ".parameters[*].name")
                                .value(containsInAnyOrder("page", "size", "sort")))
                .andExpect(
                        jsonPath(LIST + ".parameters[?(@.name=='size')].schema.maximum")
                                .value(hasItem(100)))
                .andExpect(
                        jsonPath(LIST + ".parameters[?(@.name=='size')].schema.minimum")
                                .value(hasItem(1)))
                .andExpect(
                        jsonPath(LIST + ".parameters[?(@.name=='size')].schema.default")
                                .value(hasItem(10)))
                .andExpect(
                        jsonPath(LIST + ".parameters[?(@.name=='page')].schema.minimum")
                                .value(hasItem(0)))
                .andExpect(
                        jsonPath(LIST + ".parameters[?(@.name=='sort')].description")
                                .value(
                                        hasItem(
                                                org.hamcrest.Matchers.containsString(
                                                        "name, joinedAt"))))
                .andExpect(
                        jsonPath(LIST + ".parameters[?(@.name=='sort')].description")
                                .value(hasItem(org.hamcrest.Matchers.containsString("name,asc"))));
    }

    @Test
    void listResponseUsesTheStandardEnvelope() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(
                        jsonPath(LIST + ".responses['200'].content['application/json']").exists())
                .andExpect(
                        jsonPath("$.components.schemas.PageResponseItem.properties.items").exists())
                .andExpect(
                        jsonPath("$.components.schemas.PageResponseItem.properties.totalElements")
                                .exists())
                .andExpect(
                        jsonPath("$.components.schemas.PageResponseItem.properties.totalPages")
                                .exists());
    }

    @Test
    void operationsThatTakeInputDocumentTheProblemResponses() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(
                        jsonPath(
                                        LIST
                                                + ".responses['400'].content['application/problem+json'].schema['$ref']")
                                .value("#/components/schemas/Problem"))
                .andExpect(
                        jsonPath(
                                        CREATE
                                                + ".responses['400'].content['application/problem+json'].schema['$ref']")
                                .value("#/components/schemas/Problem"))
                .andExpect(jsonPath(CREATE + ".requestBody.content['application/json']").exists());
    }

    @Test
    void everyOperationDocumentsTheUnexpectedErrorButOnlyInputOperationsDocument400()
            throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(
                        jsonPath(
                                        BOOM
                                                + ".responses['500'].content['application/problem+json'].schema['$ref']")
                                .value("#/components/schemas/Problem"))
                .andExpect(jsonPath(BOOM + ".responses['400']").doesNotExist());
    }

    @Test
    void swaggerUiIsOffByDefault() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }

    @Test
    void theDocumentItselfIsNotUnderTheApiPrefix() throws Exception {
        mvc.perform(get("/api/v1/v3/api-docs")).andExpect(status().isNotFound());
    }
}
