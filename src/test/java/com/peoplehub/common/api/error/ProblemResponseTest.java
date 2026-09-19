package com.peoplehub.common.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.support.ApiWebTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The error contract (Spec 13, 13.2): one problem body for every kind of failure. */
@ApiWebTest
@ExtendWith(OutputCaptureExtension.class)
class ProblemResponseTest {

    private static final String BASE = "/api/v1/api-test";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String INSTANCE_URN = "urn:peoplehub:request:";
    private static final String INVALID_BODY =
            "{\"name\":\"\",\"email\":\"not-an-email\",\"qty\":0,\"address\":{\"city\":\"\"}}";

    @Autowired private MockMvc mvc;

    // ---- validation
    // ------------------------------------------------------------------------------

    @Test
    void invalidBodyIsAValidationProblemWithSortedFieldErrors() throws Exception {
        mvc.perform(
                        post(BASE + "/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(INVALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:validation-error"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("One or more fields are invalid."))
                .andExpect(jsonPath("$.instance").value(startsWith(INSTANCE_URN)))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(
                        jsonPath("$.fieldErrors[*].field")
                                .value(contains("address.city", "email", "name", "qty")))
                .andExpect(
                        jsonPath("$.fieldErrors[*].message")
                                .value(everyItem(not(blankOrNullString()))));
    }

    @Test
    void aValidBodyIsAccepted() throws Exception {
        mvc.perform(
                        post(BASE + "/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"x\",\"email\":\"a@b.co\",\"qty\":1,\"address\":{\"city\":\"Pune\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("x"));
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"application/json", "*/*", "application/problem+json"})
    void errorsAreProblemJsonWhateverTheClientAccepts(String accept) throws Exception {
        mvc.perform(
                        post(BASE + "/items")
                                .accept(accept)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(INVALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void aConstraintOnARequestParameterIsReportedByName() throws Exception {
        mvc.perform(get(BASE + "/limit").param("limit", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:validation-error"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"));
    }

    @Test
    void aMissingRequiredParameterIsReportedByName() throws Exception {
        mvc.perform(get(BASE + "/limit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("is required"));
    }

    @Test
    void aWrongTypeOnAQueryParameterIsReportedWithoutEchoingTheValue() throws Exception {
        mvc.perform(get(BASE + "/limit").param("limit", "jane-abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("has an invalid value"))
                .andExpect(content().string(not(containsString("jane-abc"))));
    }

    @Test
    void aWrongTypeOnAPathVariableIsReportedWithoutEchoingTheValueAnywhere() throws Exception {
        mvc.perform(get(BASE + "/typed/jane-abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"))
                // Not even in "instance": a path variable can hold an id or a token.
                .andExpect(jsonPath("$.instance").value(startsWith(INSTANCE_URN)))
                .andExpect(content().string(not(containsString("jane-abc"))));
    }

    // ---- errors raised by Spring MVC itself -----------------------------------------------------

    @Test
    void malformedJsonIsAProblemThatDoesNotLeakParserText() throws Exception {
        mvc.perform(post(BASE + "/items").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:malformed-request"))
                .andExpect(content().string(not(containsString("Unexpected"))))
                .andExpect(content().string(not(containsString("Json"))));
    }

    @Test
    void anUnknownPathIsANotFoundProblemThatDoesNotEchoThePath() throws Exception {
        mvc.perform(get("/api/v1/secret-jane-path"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:not-found"))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andExpect(jsonPath("$.instance").value(startsWith(INSTANCE_URN)))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(not(containsString("secret-jane-path"))));
    }

    @Test
    void anUnsupportedMethodIsAProblemAndKeepsTheAllowHeader() throws Exception {
        mvc.perform(delete(BASE + "/items"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:method-not-allowed"))
                .andExpect(header().exists("Allow"));
    }

    @Test
    void anUnsupportedMediaTypeIsAProblem() throws Exception {
        mvc.perform(post(BASE + "/items").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.type").value("urn:peoplehub:problem:unsupported-media-type"));
    }

    @Test
    void anUnacceptableResponseTypeIsAProblem() throws Exception {
        mvc.perform(get(BASE + "/instant").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:not-acceptable"));
    }

    // ---- errors raised by our own code
    // -----------------------------------------------------------

    @Test
    void anApiProblemExceptionKeepsItsTypeStatusAndDetail() throws Exception {
        mvc.perform(get(BASE + "/problem"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:not-found"))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("Sample thing was not found."));
    }

    @Test
    void anUnexpectedExceptionLeaksNothingToTheClientButIsLogged(CapturedOutput output)
            throws Exception {
        String body =
                mvc.perform(get(BASE + "/boom"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:internal-error"))
                        .andExpect(jsonPath("$.correlationId").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .doesNotContain("secret")
                .doesNotContain("jane.doe")
                .doesNotContain("IllegalStateException")
                .doesNotContain("com.peoplehub")
                .doesNotContain("\tat ");
        // The cause must not be swallowed: it is recorded server-side for whoever investigates.
        assertThat(output.getAll())
                .contains("Unhandled exception")
                .contains("IllegalStateException");
    }

    @Test
    void instanceIdentifiesTheOccurrenceAndNeverTheRequestPathOrQuery() throws Exception {
        mvc.perform(
                        get(BASE + "/limit")
                                .param("limit", "9")
                                .param("search", "jane@example.com")
                                .header(CorrelationId.HEADER, "occurrence-77"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.instance").value("urn:peoplehub:request:occurrence-77"))
                .andExpect(jsonPath("$.correlationId").value("occurrence-77"))
                .andExpect(content().string(not(containsString("jane"))))
                .andExpect(content().string(not(containsString("api-test"))));
    }
}
