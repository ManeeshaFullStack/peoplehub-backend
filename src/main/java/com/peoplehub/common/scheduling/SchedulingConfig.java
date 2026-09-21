package com.peoplehub.common.scheduling;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs (Spec 2, 14.3): Spring's {@code @Scheduled} guarded by ShedLock, so a job runs on
 * one instance at a time however many are started.
 *
 * <p>A job is a non-final Spring bean (ShedLock proxies it) with one public method annotated
 * {@code @Scheduled} and {@code @SchedulerLock}, whose body calls {@link JobRunner#run}. The README
 * has a worked example.
 *
 * <p>Rules every job follows: the lock name is unique and matches the job name; the job is
 * idempotent and derives its work from persisted state (so a run after downtime catches up on what
 * it missed); day boundaries use the org timezone, computed inside the job, never a cron zone
 * hard-coded here; {@code lockAtMostFor} is longer than the job can take, because it is what frees
 * the lock if an instance dies mid-run.
 *
 * <p>The lock store is PostgreSQL ({@code shedlock} table, migration V2) using the database's own
 * clock, so instances cannot disagree through clock skew.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "${peoplehub.scheduling.default-lock-at-most-for}")
public class SchedulingConfig {

    static final String LOCK_TABLE = "shedlock";

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return createLockProvider(dataSource);
    }

    /** The one place the lock provider is configured; tests build extra "instances" through it. */
    static LockProvider createLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName(LOCK_TABLE)
                        // The shared clock is the database's, not each JVM's, so clock skew between
                        // instances cannot make two of them believe they hold the lock.
                        .usingDbTime()
                        .build());
    }
}
