package com.peoplehub.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.common.logging.ActorId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class JobRunnerTest {

    private static final String SECRET = "jane.doe@example.com";

    /** Every read advances 250 ms, so a run that reads the clock twice takes exactly 250 ms. */
    private final Clock steppingClock =
            new Clock() {
                private final AtomicLong reads = new AtomicLong();
                private final Instant origin = Instant.parse("2026-03-08T06:59:59Z");

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

    private final JobRunner runner = new JobRunner(steppingClock);
    private final Logger logger = (Logger) LoggerFactory.getLogger(JobRunner.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.INFO);
        MDC.clear();
    }

    @AfterEach
    void release() {
        logger.detachAppender(logs);
        MDC.clear();
    }

    private static String kv(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(p -> p.key.equals(key))
                .map(p -> String.valueOf(p.value))
                .findFirst()
                .orElse(null);
    }

    // ---- context while the job runs -----------------------------------------------------------

    @Test
    void runsTheJobExactlyOnce() {
        List<String> ran = new ArrayList<>();

        runner.run("payroll-sync", () -> ran.add("x"));

        assertThat(ran).hasSize(1);
    }

    @Test
    void theJobSeesItsActorIdAndAFreshCorrelationId() {
        List<String> seen = new ArrayList<>();

        runner.run(
                "payroll-sync",
                () -> {
                    seen.add(MDC.get(ActorId.MDC_KEY));
                    seen.add(MDC.get(CorrelationId.MDC_KEY));
                });

        assertThat(seen.get(0)).isEqualTo("job:payroll-sync");
        assertThat(CorrelationId.isValid(seen.get(1))).isTrue();
    }

    @Test
    void everyRunGetsItsOwnCorrelationId() {
        List<String> ids = new ArrayList<>();

        runner.run("j", () -> ids.add(MDC.get(CorrelationId.MDC_KEY)));
        runner.run("j", () -> ids.add(MDC.get(CorrelationId.MDC_KEY)));

        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void nothingLeaksIntoTheThreadAfterASuccessfulRun() {
        runner.run("j", () -> {});

        assertThat(MDC.get(ActorId.MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void nothingLeaksIntoTheThreadAfterAFailedRun() {
        runner.run(
                "j",
                () -> {
                    throw new IllegalStateException(SECRET);
                });

        assertThat(MDC.get(ActorId.MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void whateverWasOnTheThreadBeforeIsRestoredAfterwards() {
        // e.g. a job triggered from inside a request
        ActorId.set("42");
        MDC.put(CorrelationId.MDC_KEY, "request-corr-1");

        runner.run("j", () -> assertThat(ActorId.current()).isEqualTo("job:j"));

        assertThat(ActorId.current()).isEqualTo("42");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("request-corr-1");
    }

    @Test
    void concurrentRunsDoNotSeeEachOthersContext() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        List<String> seenByA = new ArrayList<>();
        List<String> seenByB = new ArrayList<>();

        Thread a = new Thread(() -> runner.run("job-a", () -> observe(bothInside, seenByA)));
        Thread b = new Thread(() -> runner.run("job-b", () -> observe(bothInside, seenByB)));
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(seenByA).containsOnly("job:job-a");
        assertThat(seenByB).containsOnly("job:job-b");
    }

    private static void observe(CountDownLatch bothInside, List<String> into) {
        bothInside.countDown();
        try {
            bothInside.await(5, TimeUnit.SECONDS); // overlap in time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        into.add(MDC.get(ActorId.MDC_KEY));
    }

    // ---- what is logged -----------------------------------------------------------------------

    @Test
    void logsOneStartAndOneFinishLineWithTheOutcomeAndDuration() {
        runner.run("payroll-sync", () -> {});

        assertThat(logs.list).hasSize(2);
        ILoggingEvent started = logs.list.get(0);
        ILoggingEvent finished = logs.list.get(1);
        assertThat(started.getFormattedMessage()).isEqualTo("Job started");
        assertThat(kv(started, "job")).isEqualTo("payroll-sync");
        assertThat(finished.getFormattedMessage()).isEqualTo("Job finished");
        assertThat(finished.getLevel()).isEqualTo(Level.INFO);
        assertThat(kv(finished, "job")).isEqualTo("payroll-sync");
        assertThat(kv(finished, "outcome")).isEqualTo("SUCCESS");
        assertThat(kv(finished, "durationMs")).isEqualTo("250");
    }

    @Test
    void everyLineOfARunCarriesTheActorAndCorrelationId() {
        runner.run("payroll-sync", () -> {});

        assertThat(logs.list)
                .allSatisfy(
                        event -> {
                            assertThat(event.getMDCPropertyMap())
                                    .containsEntry(ActorId.MDC_KEY, "job:payroll-sync");
                            assertThat(event.getMDCPropertyMap())
                                    .containsKey(CorrelationId.MDC_KEY);
                        });
        assertThat(logs.list.get(0).getMDCPropertyMap().get(CorrelationId.MDC_KEY))
                .isEqualTo(logs.list.get(1).getMDCPropertyMap().get(CorrelationId.MDC_KEY));
    }

    // ---- failures -----------------------------------------------------------------------------

    @Test
    void aFailingJobIsLoggedOnceAtErrorWithItsCauseAndOutcome() {
        runner.run(
                "payroll-sync",
                () -> {
                    throw new IllegalStateException(SECRET);
                });

        List<ILoggingEvent> errors =
                logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        ILoggingEvent failed = errors.get(0);
        assertThat(failed.getFormattedMessage()).isEqualTo("Job failed");
        assertThat(kv(failed, "job")).isEqualTo("payroll-sync");
        assertThat(kv(failed, "outcome")).isEqualTo("FAILURE");
        assertThat(kv(failed, "durationMs")).isEqualTo("250");
        assertThat(failed.getThrowableProxy().getClassName())
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(failed.getMDCPropertyMap()).containsEntry(ActorId.MDC_KEY, "job:payroll-sync");
        assertThat(failed.getMDCPropertyMap()).containsKey(CorrelationId.MDC_KEY);
    }

    @Test
    void theFailureMessageNeverContainsTheExceptionText() {
        runner.run(
                "payroll-sync",
                () -> {
                    throw new IllegalStateException(SECRET);
                });

        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains(SECRET));
    }

    @Test
    void aFailingJobDoesNotStopTheScheduleAndTheNextRunWorks() {
        List<String> ran = new ArrayList<>();

        runner.run(
                "j",
                () -> {
                    throw new IllegalStateException("boom");
                });
        runner.run("j", () -> ran.add("second run"));

        assertThat(ran).containsExactly("second run");
    }

    @Test
    void aFailedRunDoesNotLogAFinishedLine() {
        runner.run(
                "j",
                () -> {
                    throw new IllegalStateException("boom");
                });

        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Job started", "Job failed");
    }

    @Test
    void anErrorIsNotSwallowedButTheContextIsStillCleaned() {
        assertThatThrownBy(
                        () ->
                                runner.run(
                                        "j",
                                        () -> {
                                            throw new AssertionError("really bad");
                                        }))
                .isInstanceOf(AssertionError.class);

        assertThat(MDC.get(ActorId.MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    // ---- job names ----------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] is rejected")
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "has space",
                "job:colon",
                "line\nbreak",
                "jane.doe@example.com",
                "slash/name",
                "0123456789012345678901234567890123456789X" // 41 characters
            })
    void rejectsANameThatIsNotAPlainShortIdentifier(String name) {
        List<String> ran = new ArrayList<>();

        assertThatThrownBy(() -> runner.run(name, () -> ran.add("x")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ran).as("the job must not run under a bad name").isEmpty();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void acceptsANameOfExactlyTheMaximumLength() {
        String longest = "x".repeat(JobRunner.MAX_NAME_LENGTH);

        runner.run(longest, () -> assertThat(ActorId.current()).isEqualTo("job:" + longest));
    }
}
