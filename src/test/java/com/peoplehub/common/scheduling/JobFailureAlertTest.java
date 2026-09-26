package com.peoplehub.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.common.scheduling.testsupport.ProbeJob;
import com.peoplehub.common.scheduling.testsupport.ProbeJobSchema;
import com.peoplehub.support.CapturingSentryTransportConfig;
import com.peoplehub.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Job failures alert (not silent)" (Spec 14.2, 14.3), end to end: a scheduled job that throws is
 * logged once as ERROR and reaches Sentry once, tagged with the job's actor id and a correlation
 * id, carrying no exception message. The job runs once, right after startup.
 */
@IntegrationTest
@ActiveProfiles("scheduler-test")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
        properties = {
            "sentry.dsn=https://public@localhost:1/1",
            "probe.fail=true",
            "probe.every=PT1H"
        })
@Import({CapturingSentryTransportConfig.class, ProbeJobSchema.class})
class JobFailureAlertTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AfterAll
    static void closeSentry() {
        CapturingSentryTransportConfig.reset();
    }

    private static List<String> failureEvents() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        List<String> events = CapturingSentryTransportConfig.events();
        while (events.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200); // the job runs asynchronously right after startup
            events = CapturingSentryTransportConfig.events();
        }
        return events;
    }

    @Test
    void aFailedJobIsReportedToSentryOnceWithItsIdentityAndNoExceptionText() throws Exception {
        List<String> events = failureEvents();
        Thread.sleep(1500); // room for a duplicate report to show up, if there were one
        events = CapturingSentryTransportConfig.events();

        assertThat(events).as("one event for one failed run").hasSize(1);
        assertThat(events.get(0))
                .contains("\"actorId\":\"job:probe-job\"")
                .contains("\"correlationId\":\"")
                .contains("IllegalStateException")
                .contains("ProbeJob")
                .doesNotContain(ProbeJob.SECRET_IN_FAILURE)
                .doesNotContain("jane.doe");
    }

    @Test
    void theFailureIsLoggedOnceAsErrorWithTheJobIdentityAndNoExceptionText(CapturedOutput output)
            throws Exception {
        failureEvents(); // the run has certainly happened by now

        List<JsonNode> failures =
                output.getAll()
                        .lines()
                        .filter(l -> l.contains("\"message\":\"Job failed\""))
                        .map(JSON::readTree)
                        .toList();
        assertThat(failures).hasSize(1);
        JsonNode failure = failures.get(0);
        assertThat(failure.get("level").asString()).isEqualTo("ERROR");
        assertThat(failure.get("job").asString()).isEqualTo("probe-job");
        assertThat(failure.get("outcome").asString()).isEqualTo("FAILURE");
        assertThat(failure.get("actorId").asString()).isEqualTo("job:probe-job");
        assertThat(failure.get("correlationId").asString()).isNotBlank();
        assertThat(failure.get("stack_trace").asString())
                .contains("java.lang.IllegalStateException");
        assertThat(output.getAll()).doesNotContain(ProbeJob.SECRET_IN_FAILURE);
    }
}
