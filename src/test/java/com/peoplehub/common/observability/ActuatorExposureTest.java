package com.peoplehub.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What Actuator publishes (Spec 14.2, 16.4): health and the two probes, and nothing else. Actuator
 * is an unauthenticated surface until B2, so anything beyond health must be unreachable.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ActuatorExposureTest {

    @Autowired private MockMvc mvc;
    @Autowired private HealthEndpointGroups groups;

    // ---- probes -----------------------------------------------------------------------------

    @Test
    void livenessIsUpAndReportsNothingButStatus() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void readinessIsUpAndReportsNothingButStatus() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void theOverallHealthEndpointReportsOnlyStatusAndTheGroupNames() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .json(
                                        "{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}",
                                        true));
    }

    @Test
    void readinessChecksTheDatabaseAndRedis() {
        assertThat(groups.get("readiness")).isNotNull();
        assertThat(groups.get("readiness").isMember("db")).isTrue();
        assertThat(groups.get("readiness").isMember("redis")).isTrue();
    }

    @Test
    void livenessDoesNotDependOnTheDatabaseOrRedis() {
        // A database or Redis blip must not get the container restarted.
        assertThat(groups.get("liveness")).isNotNull();
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(groups.get("liveness").isMember("redis")).isFalse();
    }

    @Test
    void healthResponsesStillCarryACorrelationId() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(header().exists(CorrelationId.HEADER));
    }

    @Test
    void healthChecksAreNotWrittenToTheRequestLog(CapturedOutput output) throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        assertThat(output.getAll()).doesNotContain("HTTP request completed");
    }

    // ---- nothing else is exposed ------------------------------------------------------------

    @ParameterizedTest(name = "/actuator/{0} is not exposed")
    @ValueSource(
            strings = {
                "env",
                "beans",
                "metrics",
                "info",
                "loggers",
                "configprops",
                "mappings",
                "heapdump",
                "threaddump",
                "httpexchanges",
                "conditions",
                "scheduledtasks",
                "shutdown",
                "prometheus",
                "sentry"
            })
    void everyEndpointBesidesHealthIsUnreachable(String endpoint) throws Exception {
        mvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
    }

    @Test
    void theActuatorIndexDoesNotAdvertiseAnythingButHealth() throws Exception {
        String body = mvc.perform(get("/actuator")).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("env").doesNotContain("metrics").doesNotContain("beans");
    }

    @Test
    void anUnexposedEndpointStillAnswersWithTheStandardProblemBody() throws Exception {
        mvc.perform(get("/actuator/env"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:not-found"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
