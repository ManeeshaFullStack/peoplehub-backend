package com.peoplehub.common.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An unreachable SMTP server must never affect application health (b1-2 regression; CLAUDE.md D1,
 * Spec 9.2). Spring Boot auto-registers a mail health contributor the moment {@code
 * spring-boot-starter-mail} sees a {@code JavaMailSender} bean, and the root {@code
 * /actuator/health} endpoint aggregates every registered contributor -- unlike liveness/readiness,
 * whose members are the explicit allowlist in {@code application.yml}. {@code
 * management.health.mail.enabled=false} keeps it out.
 *
 * <p>Points {@code spring.mail.host} at a deliberately unroutable address (RFC 5737 TEST-NET-3)
 * rather than trusting whatever may or may not be listening on the default host/port on a given
 * machine: that exact coincidence (an unrelated container happening to occupy the default port) is
 * what let this regression pass on a developer machine while genuinely failing in CI, where nothing
 * is listening. With the fix in place nothing ever attempts to connect to this address at all, so
 * the test is fast regardless of whether it would time out.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.mail.host=203.0.113.1", "spring.mail.port=1025"})
@DirtiesContext
class MailHealthDoesNotAffectOverallHealthTest {

    @Autowired private MockMvc mvc;

    @Test
    void rootHealthStaysUpEvenThoughSmtpIsUnreachable() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .json(
                                        "{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}",
                                        true));
    }

    @Test
    void readinessAndLivenessAlsoStayUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }
}
