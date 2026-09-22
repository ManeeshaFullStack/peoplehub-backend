package com.peoplehub.notification.email;

import com.peoplehub.common.scheduling.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules {@link EmailOutboxProcessor} (b1-2, Spec 9.2, 14.3): a thin {@code @Scheduled} +
 * {@code @SchedulerLock} wrapper, the exact shape {@code common/scheduling}'s README documents (and
 * {@code ProbeJob} demonstrates in tests) -- one method, whose body is only {@code
 * jobRunner.run(name, () -> ...)}. All the actual logic lives in {@link EmailOutboxProcessor},
 * which is plain and testable without Spring or a scheduler.
 *
 * <p>Not profile-guarded: unlike the test-only {@code ProbeJob}, this is the real production job
 * and runs in every environment. It is safe to run against a database with no due rows -- the
 * common case in most tests -- since the query is bounded and indexed ({@code
 * idx_email_outbox_due}, V5) and simply finds nothing to do.
 */
@Component
public class EmailOutboxProcessorJob {

    public static final String JOB_NAME = "email-outbox-processor";

    private final JobRunner jobRunner;
    private final EmailOutboxProcessor processor;

    public EmailOutboxProcessorJob(JobRunner jobRunner, EmailOutboxProcessor processor) {
        this.jobRunner = jobRunner;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${peoplehub.email.outbox.interval:30s}")
    @SchedulerLock(
            name = JOB_NAME,
            lockAtMostFor = "${peoplehub.email.outbox.lock-at-most-for:PT2M}")
    public void run() {
        jobRunner.run(JOB_NAME, processor::run);
    }
}
