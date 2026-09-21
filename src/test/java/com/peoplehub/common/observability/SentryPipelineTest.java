package com.peoplehub.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.support.IntegrationTest;
import io.sentry.Hint;
import io.sentry.ITransportFactory;
import io.sentry.Sentry;
import io.sentry.SentryEnvelope;
import io.sentry.SentryOptions;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The real Sentry pipeline, end to end (Spec 14.2, 15): the application is started with a DSN, an
 * unexpected error is triggered over HTTP, and the envelope that would leave the process is
 * inspected. Only the network transport is replaced, so the SDK, the Logback appender and {@link
 * SentryEventScrubber} are the production ones.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ActiveProfiles("api-test")
@DirtiesContext
@TestPropertySource(properties = "sentry.dsn=https://public@localhost:1/1")
@Import(SentryPipelineTest.CapturingTransportConfig.class)
class SentryPipelineTest {

    private static final String BASE = "/api/v1/api-test";
    private static final List<String> ENVELOPES = new CopyOnWriteArrayList<>();

    @Autowired private MockMvc mvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class CapturingTransportConfig {

        @Bean
        ITransportFactory capturingTransportFactory() {
            return (options, requestDetails) -> new CapturingTransport(options);
        }
    }

    /** Records every envelope the SDK tries to send, as the JSON that would go over the wire. */
    static class CapturingTransport implements ITransport {

        private final SentryOptions options;

        CapturingTransport(SentryOptions options) {
            this.options = options;
        }

        @Override
        public void send(SentryEnvelope envelope, Hint hint) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                options.getSerializer().serialize(envelope, out);
            } catch (Exception e) {
                throw new IOException(e);
            }
            ENVELOPES.add(out.toString(StandardCharsets.UTF_8));
        }

        @Override
        public void flush(long timeoutMillis) {}

        @Override
        public RateLimiter getRateLimiter() {
            return null;
        }

        @Override
        public void close() {}

        @Override
        public void close(boolean isRestarting) {}
    }

    @BeforeEach
    void clear() {
        ENVELOPES.clear();
    }

    @AfterAll
    static void closeSentry() {
        Sentry.close();
    }

    private List<String> events() {
        Sentry.flush(5_000);
        return ENVELOPES.stream().filter(e -> e.contains("\"type\":\"event\"")).toList();
    }

    @Test
    void anUnexpectedErrorIsReportedToSentry() throws Exception {
        String correlationId =
                mvc.perform(get(BASE + "/boom"))
                        .andExpect(status().isInternalServerError())
                        .andReturn()
                        .getResponse()
                        .getHeader(CorrelationId.HEADER);

        List<String> events = events();

        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .contains("IllegalStateException")
                .contains("SampleApiController")
                .contains("\"level\":\"error\"")
                // The tags that tie the event to the logs of the same request.
                .contains("\"correlationId\":\"" + correlationId + "\"")
                .contains("\"actorId\":\"anonymous\"");
    }

    @Test
    void theEventCarriesNoPersonalDataAndNoRequestData() throws Exception {
        mvc.perform(
                        get(BASE + "/boom")
                                .queryParam("token", "s3cr3t-approval-token")
                                .header("Authorization", "Bearer eyJ-secret-token")
                                .header("Cookie", "refresh=cookie-secret"))
                .andExpect(status().isInternalServerError());

        List<String> events = events();

        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                // The exception message, which quotes the address.
                .doesNotContain("jane.doe")
                .doesNotContain("secret internal detail")
                // The request: URL, query, headers, cookies.
                .doesNotContain("s3cr3t-approval-token")
                .doesNotContain("eyJ-secret-token")
                .doesNotContain("cookie-secret")
                .doesNotContain("api-test/boom")
                .doesNotContain("\"request\"")
                .doesNotContain("\"user\"")
                .doesNotContain("breadcrumbs");
    }

    @Test
    void aUniqueViolationMessageIsNotReported() throws Exception {
        mvc.perform(get(BASE + "/integrity")).andExpect(status().isInternalServerError());

        List<String> events = events();

        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .contains("DataIntegrityViolationException")
                .doesNotContain("jane.doe")
                .doesNotContain("uq_employee_email")
                .doesNotContain("duplicate key")
                .doesNotContain("Key (email)");
    }

    @Test
    void handledClientErrorsAreNotReported() throws Exception {
        mvc.perform(get(BASE + "/problem")).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/typed/not-a-number")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/nothing/here")).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/items").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/limit").queryParam("limit", "99"))
                .andExpect(status().isBadRequest());

        assertThat(events()).isEmpty();
    }
}
