package com.peoplehub.common.scheduling;

import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.common.logging.ActorId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Runs the body of a scheduled job with the same observability a request gets (Spec 14.1, 14.2,
 * 14.3): a fresh correlation id and the actor {@code job:<name>} in MDC for every log line the job
 * writes, one start and one finish line with the duration, and, when the job fails, exactly one
 * ERROR line. That ERROR is what reaches Sentry, tagged with the job's actor id and correlation id,
 * so a failed job is never silent.
 *
 * <p>The failure is logged, not rethrown: one bad run must not stop the schedule, and rethrowing
 * would make the scheduler log the same failure a second time. Only {@link Exception}s are caught;
 * an {@link Error} (out of memory and the like) still propagates.
 *
 * <p>Whatever MDC values were on the thread before the run (for example when a job is triggered
 * from a request) are restored afterwards, so nothing leaks either way.
 *
 * <p>The job name is also the ShedLock name and the actor id, so it is restricted to characters
 * that are safe in a log line and short enough for the {@code shedlock.name} column.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    static final String ACTOR_PREFIX = "job:";
    static final int MAX_NAME_LENGTH = 40;

    private final Clock clock;

    public JobRunner(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param jobName stable name of the job, {@code [A-Za-z0-9._-]}, at most {@value
     *     #MAX_NAME_LENGTH} characters; must match the job's {@code @SchedulerLock} name
     * @param job the work; it should be idempotent and derive what to do from persisted state
     */
    public void run(String jobName, Runnable job) {
        validate(jobName);
        String previousActor = MDC.get(ActorId.MDC_KEY);
        String previousCorrelation = MDC.get(CorrelationId.MDC_KEY);
        ActorId.set(ACTOR_PREFIX + jobName);
        MDC.put(CorrelationId.MDC_KEY, CorrelationId.generate());
        Instant start = clock.instant();
        try {
            log.atInfo().addKeyValue("job", jobName).log("Job started");
            try {
                job.run();
            } catch (Exception e) {
                log.atError()
                        .addKeyValue("job", jobName)
                        .addKeyValue("outcome", "FAILURE")
                        .addKeyValue("durationMs", millisSince(start))
                        .setCause(e)
                        .log("Job failed");
                return;
            }
            log.atInfo()
                    .addKeyValue("job", jobName)
                    .addKeyValue("outcome", "SUCCESS")
                    .addKeyValue("durationMs", millisSince(start))
                    .log("Job finished");
        } finally {
            restore(ActorId.MDC_KEY, previousActor);
            restore(CorrelationId.MDC_KEY, previousCorrelation);
        }
    }

    private long millisSince(Instant start) {
        return Duration.between(start, clock.instant()).toMillis();
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }

    private static void validate(String jobName) {
        if (jobName == null
                || jobName.isEmpty()
                || jobName.length() > MAX_NAME_LENGTH
                || !jobName.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "Job name must be 1-" + MAX_NAME_LENGTH + " characters of [A-Za-z0-9._-]");
        }
    }
}
