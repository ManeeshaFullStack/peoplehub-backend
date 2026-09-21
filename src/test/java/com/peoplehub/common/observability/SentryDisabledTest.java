package com.peoplehub.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.IntegrationTest;
import io.sentry.Sentry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sentry is off unless a DSN is configured: {@code SENTRY_DSN} is an empty value in {@code
 * .env.example}, and an empty value must mean "disabled", not "fail to start" or "send somewhere".
 */
@IntegrationTest
@AutoConfigureMockMvc
@ActiveProfiles("api-test")
@DirtiesContext
@TestPropertySource(properties = "sentry.dsn=")
class SentryDisabledTest {

    @Autowired private MockMvc mvc;
    @Autowired private SentryEventScrubber scrubber;

    @BeforeAll
    static void startFromANeutralSdk() {
        // The SDK is a process-wide singleton; make sure another test's instance is not observed.
        Sentry.close();
    }

    @Test
    void anEmptyDsnStartsTheApplicationWithSentryDisabled() {
        assertThat(Sentry.isEnabled()).isFalse();
        assertThat(scrubber).isNotNull();
    }

    @Test
    void errorsAreStillHandledNormallyWithSentryDisabled() throws Exception {
        mvc.perform(get("/api/v1/api-test/boom")).andExpect(status().isInternalServerError());
    }
}
