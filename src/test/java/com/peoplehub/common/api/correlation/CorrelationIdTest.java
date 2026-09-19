package com.peoplehub.common.api.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.ApiWebTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@ApiWebTest
class CorrelationIdTest {

    private static final String INSTANT = "/api/v1/api-test/instant";
    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired private MockMvc mvc;

    @Test
    void generatesAnIdWhenTheCallerSendsNone() throws Exception {
        String id =
                mvc.perform(get(INSTANT))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getHeader(CorrelationId.HEADER);

        assertThat(id).matches(UUID_PATTERN);
    }

    @Test
    void propagatesAWellFormedIdFromTheCaller() throws Exception {
        mvc.perform(get(INSTANT).header(CorrelationId.HEADER, "client-req.42_A"))
                .andExpect(header().string(CorrelationId.HEADER, "client-req.42_A"));
    }

    @ParameterizedTest(name = "[{0}] is replaced")
    @ValueSource(strings = {"id with spaces", "<script>", "semi;colon", "a/b", " "})
    void replacesAnIdContainingUnsafeCharacters(String unsafe) throws Exception {
        String id =
                mvc.perform(get(INSTANT).header(CorrelationId.HEADER, unsafe))
                        .andReturn()
                        .getResponse()
                        .getHeader(CorrelationId.HEADER);

        assertThat(id).isNotEqualTo(unsafe).matches(UUID_PATTERN);
    }

    @Test
    void replacesAnOversizedId() throws Exception {
        String oversized = "x".repeat(65);

        String id =
                mvc.perform(get(INSTANT).header(CorrelationId.HEADER, oversized))
                        .andReturn()
                        .getResponse()
                        .getHeader(CorrelationId.HEADER);

        assertThat(id).matches(UUID_PATTERN);
    }

    @Test
    void acceptsAnIdOfExactlyTheMaximumLength() throws Exception {
        String longest = "x".repeat(64);

        mvc.perform(get(INSTANT).header(CorrelationId.HEADER, longest))
                .andExpect(header().string(CorrelationId.HEADER, longest));
    }

    @Test
    void everyRequestGetsItsOwnId() throws Exception {
        String first =
                mvc.perform(get(INSTANT)).andReturn().getResponse().getHeader(CorrelationId.HEADER);
        String second =
                mvc.perform(get(INSTANT)).andReturn().getResponse().getHeader(CorrelationId.HEADER);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void errorBodiesRepeatTheIdReturnedInTheHeader() throws Exception {
        mvc.perform(get("/api/v1/api-test/problem").header(CorrelationId.HEADER, "trace-me-123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(CorrelationId.HEADER, "trace-me-123"))
                .andExpect(jsonPath("$.correlationId").value("trace-me-123"));
    }

    @Test
    void requestsThatMatchNoHandlerStillGetAnId() throws Exception {
        mvc.perform(get("/nothing/here"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists(CorrelationId.HEADER));
    }

    @Test
    void theIdDoesNotLeakIntoTheThreadAfterTheRequest() throws Exception {
        mvc.perform(get(INSTANT)).andExpect(status().isOk());

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
        assertThat(CorrelationId.current()).isNull();
    }

    @Test
    void theIdDoesNotLeakIntoTheThreadAfterAFailedRequest() throws Exception {
        mvc.perform(get("/api/v1/api-test/boom")).andExpect(status().isInternalServerError());

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }
}
