package com.peoplehub.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.support.ApiWebTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request log and the MDC entries around it (Spec 14.1): one JSON line per API call with
 * method, route template, status, duration, correlation id and actor id, and nothing the client
 * controls beyond that.
 */
@ApiWebTest
@ExtendWith(OutputCaptureExtension.class)
@Import(RequestLoggingTest.SteppingClockConfig.class)
class RequestLoggingTest {

    private static final String BASE = "/api/v1/api-test";
    private static final String MESSAGE = "HTTP request completed";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;

    /**
     * Every read of this clock advances 250 ms, so a filter that reads it once at the start and
     * once at the end of a request must report exactly 250 ms.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SteppingClockConfig {

        @Bean
        @Primary
        Clock steppingClock() {
            AtomicLong reads = new AtomicLong();
            Instant origin = Instant.parse("2026-03-08T06:59:59Z");
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return origin.plus(Duration.ofMillis(250 * reads.getAndIncrement()));
                }
            };
        }
    }

    private static List<JsonNode> requestLines(CapturedOutput output) {
        return output.getAll()
                .lines()
                .filter(l -> l.contains(MESSAGE))
                .map(RequestLoggingTest::parse)
                .toList();
    }

    private static JsonNode parse(String line) {
        return JSON.readTree(line);
    }

    // ---- what is logged ---------------------------------------------------------------------

    @Test
    void logsOneJsonLinePerRequestWithMethodRouteStatusAndDuration(CapturedOutput output)
            throws Exception {
        mvc.perform(get(BASE + "/instant")).andExpect(status().isOk());

        List<JsonNode> lines = requestLines(output);
        assertThat(lines).hasSize(1);
        JsonNode line = lines.get(0);
        assertThat(line.get("message").asString()).isEqualTo(MESSAGE);
        assertThat(line.get("level").asString()).isEqualTo("INFO");
        assertThat(line.get("method").asString()).isEqualTo("GET");
        assertThat(line.get("route").asString()).isEqualTo(BASE + "/instant");
        assertThat(line.get("status").asInt()).isEqualTo(200);
        assertThat(line.get("durationMs").asLong()).isEqualTo(250);
    }

    @Test
    void theLineCarriesTheSameCorrelationIdThatTheClientReceives(CapturedOutput output)
            throws Exception {
        String id =
                mvc.perform(get(BASE + "/instant"))
                        .andReturn()
                        .getResponse()
                        .getHeader(CorrelationId.HEADER);

        assertThat(requestLines(output).get(0).get("correlationId").asString()).isEqualTo(id);
    }

    @Test
    void theLineCarriesTheActorId(CapturedOutput output) throws Exception {
        mvc.perform(get(BASE + "/instant"));

        // No authentication until B2, so every request is anonymous.
        assertThat(requestLines(output).get(0).get("actorId").asString()).isEqualTo("anonymous");
    }

    @Test
    void logsTheStatusOfErrorResponses(CapturedOutput output) throws Exception {
        mvc.perform(get(BASE + "/problem")).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/boom")).andExpect(status().isInternalServerError());
        mvc.perform(post(BASE + "/items").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(requestLines(output))
                .extracting(l -> l.get("status").asInt())
                .containsExactly(404, 500, 400);
    }

    // ---- what is never logged ---------------------------------------------------------------

    @Test
    void logsTheRouteTemplateNotTheRawPathOrItsIds(CapturedOutput output) throws Exception {
        mvc.perform(get(BASE + "/typed/987654321"));

        JsonNode line = requestLines(output).get(0);
        assertThat(line.get("route").asString()).isEqualTo(BASE + "/typed/{id}");
        assertThat(output.getAll()).doesNotContain("987654321");
    }

    @Test
    void neverLogsTheQueryString(CapturedOutput output) throws Exception {
        mvc.perform(get(BASE + "/instant").queryParam("token", "s3cr3t-approval-token"));

        assertThat(output.getAll())
                .doesNotContain("s3cr3t-approval-token")
                .doesNotContain("token=");
    }

    @Test
    void aRequestThatMatchesNoHandlerIsLoggedWithoutItsPath(CapturedOutput output)
            throws Exception {
        mvc.perform(get("/api/v1/employees/jane.doe@example.com/secret-path"))
                .andExpect(status().isNotFound());

        JsonNode line = requestLines(output).get(0);
        assertThat(line.get("route").asString()).isEqualTo("UNMATCHED");
        assertThat(line.get("status").asInt()).isEqualTo(404);
        assertThat(output.getAll()).doesNotContain("jane.doe").doesNotContain("secret-path");
    }

    @Test
    void neverLogsRequestHeadersOrBodies(CapturedOutput output) throws Exception {
        mvc.perform(
                post(BASE + "/items")
                        .header("Authorization", "Bearer eyJ-secret-token")
                        .header("Cookie", "refresh=cookie-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"body-secret\",\"email\":\"jane.doe@example.com\"}"));

        assertThat(requestLines(output)).hasSize(1);
        assertThat(output.getAll())
                .doesNotContain("eyJ-secret-token")
                .doesNotContain("cookie-secret")
                .doesNotContain("body-secret")
                .doesNotContain("jane.doe@example.com");
    }

    @Test
    void healthChecksAreNotLogged(CapturedOutput output) throws Exception {
        // The health endpoint itself is not part of this slice (the response is 404), but the
        // filter must skip these paths regardless of what answers them.
        mvc.perform(get("/actuator/health"));
        mvc.perform(get("/actuator/health/liveness"));
        mvc.perform(get("/actuator/health/readiness"));

        assertThat(requestLines(output)).isEmpty();
    }

    @Test
    void aPathThatOnlyStartsWithTheActuatorNameIsStillLogged(CapturedOutput output)
            throws Exception {
        mvc.perform(get("/actuator-not-really"));

        assertThat(requestLines(output)).hasSize(1);
    }

    // ---- MDC hygiene ------------------------------------------------------------------------

    @Test
    void theActorIdDoesNotLeakIntoTheThreadAfterTheRequest() throws Exception {
        mvc.perform(get(BASE + "/instant")).andExpect(status().isOk());

        assertThat(MDC.get(ActorId.MDC_KEY)).isNull();
        assertThat(ActorId.current()).isNull();
    }

    @Test
    void theActorIdDoesNotLeakAfterAFailedRequest() throws Exception {
        mvc.perform(get(BASE + "/boom")).andExpect(status().isInternalServerError());

        assertThat(MDC.get(ActorId.MDC_KEY)).isNull();
    }

    @Test
    void errorLogLinesCarryTheCorrelationIdAndActorIdToo(CapturedOutput output) throws Exception {
        String id =
                mvc.perform(get(BASE + "/boom"))
                        .andReturn()
                        .getResponse()
                        .getHeader(CorrelationId.HEADER);

        JsonNode error =
                output.getAll()
                        .lines()
                        .filter(l -> l.contains("Unhandled exception"))
                        .map(RequestLoggingTest::parse)
                        .findFirst()
                        .orElseThrow();
        assertThat(error.get("level").asString()).isEqualTo("ERROR");
        assertThat(error.get("correlationId").asString()).isEqualTo(id);
        assertThat(error.get("actorId").asString()).isEqualTo("anonymous");
    }

    @Test
    void everyLogLineIsOneJsonObject(CapturedOutput output) throws Exception {
        mvc.perform(get(BASE + "/boom"));
        mvc.perform(get(BASE + "/instant"));

        List<String> appLines =
                output.getAll().lines().filter(l -> l.contains("com.peoplehub")).toList();
        assertThat(appLines).isNotEmpty();
        appLines.forEach(line -> assertThat(parse(line).isObject()).isTrue());
    }
}
