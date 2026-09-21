package com.peoplehub.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

/**
 * Why liveness and readiness are separate (Spec 16.4): when Redis goes away the application must
 * report itself not ready, but it must stay alive, so the orchestrator stops sending it traffic
 * without restarting it. Stops this test's own Redis container, so it needs a context of its own,
 * discarded afterwards.
 */
@IntegrationTest
@AutoConfigureMockMvc
@DirtiesContext
class ProbeOutageTest {

    private static final String[] PROBES = {
        "/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness"
    };

    @Autowired private MockMvc mvc;

    @Autowired
    @Qualifier("redisContainer")
    private GenericContainer<?> redis;

    @Test
    void whenRedisIsDownReadinessFailsButLivenessStaysUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());

        redis.stop();
        assertThat(redis.isRunning()).isFalse();

        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}", true));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(
                        content()
                                .json(
                                        "{\"status\":\"DOWN\",\"groups\":[\"liveness\",\"readiness\"]}",
                                        true));
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));

        // Even while degraded, nothing about the outage is published.
        for (String path : PROBES) {
            String body = mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
            assertThat(body)
                    .as(path)
                    .doesNotContain("redis")
                    .doesNotContain("Redis")
                    .doesNotContain("Lettuce")
                    .doesNotContain("Unable to connect")
                    .doesNotContain("6379")
                    .doesNotContain("localhost")
                    .doesNotContain("xception");
        }
    }
}
