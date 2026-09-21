package com.peoplehub.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The lock behaves the way "a job runs on one instance at a time" needs (Spec 2, 14.3), against a
 * real PostgreSQL. Each "instance" is its own {@link LockProvider} built through the production
 * factory, so this exercises the real configuration, including the database clock.
 */
@IntegrationTest
class LockProviderTest {

    @Autowired private DataSource dataSource;

    private LockProvider instance() {
        return SchedulingConfig.createLockProvider(dataSource);
    }

    private static LockConfiguration lock(String name, Duration atMost, Duration atLeast) {
        return new LockConfiguration(Instant.now(), name, atMost, atLeast);
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void ofManyInstancesRacingForTheSameLockExactlyOneWins() throws Exception {
        int instances = 8;
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        try {
            for (int round = 0; round < 25; round++) {
                String name = uniqueName("race");
                CyclicBarrier startTogether = new CyclicBarrier(instances);
                List<Future<Optional<SimpleLock>>> attempts = new ArrayList<>();
                for (int i = 0; i < instances; i++) {
                    LockProvider provider = instance();
                    Callable<Optional<SimpleLock>> attempt =
                            () -> {
                                startTogether.await();
                                return provider.lock(
                                        lock(name, Duration.ofMinutes(1), Duration.ZERO));
                            };
                    attempts.add(pool.submit(attempt));
                }
                List<SimpleLock> winners = new ArrayList<>();
                for (Future<Optional<SimpleLock>> attempt : attempts) {
                    attempt.get().ifPresent(winners::add);
                }

                assertThat(winners).as("round %d", round).hasSize(1);
                winners.get(0).unlock();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aHeldLockBlocksOtherInstancesUntilItIsReleased() {
        String name = uniqueName("held");
        LockProvider holder = instance();
        LockProvider other = instance();

        SimpleLock held =
                holder.lock(lock(name, Duration.ofMinutes(1), Duration.ZERO)).orElseThrow();
        assertThat(other.lock(lock(name, Duration.ofMinutes(1), Duration.ZERO))).isEmpty();

        held.unlock();

        Optional<SimpleLock> afterRelease =
                other.lock(lock(name, Duration.ofMinutes(1), Duration.ZERO));
        assertThat(afterRelease).isPresent();
        afterRelease.get().unlock();
    }

    @Test
    void differentLockNamesDoNotBlockEachOther() {
        LockProvider a = instance();
        LockProvider b = instance();

        Optional<SimpleLock> first =
                a.lock(lock(uniqueName("one"), Duration.ofMinutes(1), Duration.ZERO));
        Optional<SimpleLock> second =
                b.lock(lock(uniqueName("two"), Duration.ofMinutes(1), Duration.ZERO));

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        first.get().unlock();
        second.get().unlock();
    }

    @Test
    void aCrashedHoldersLockIsFreedAfterLockAtMostFor() throws Exception {
        String name = uniqueName("crash");
        // The holder takes the lock and never releases it, as if its instance had died.
        instance().lock(lock(name, Duration.ofSeconds(2), Duration.ZERO)).orElseThrow();
        LockProvider survivor = instance();

        assertThat(survivor.lock(lock(name, Duration.ofSeconds(2), Duration.ZERO)))
                .as("still held right after the crash")
                .isEmpty();

        Thread.sleep(3000);

        Optional<SimpleLock> afterExpiry =
                instance().lock(lock(name, Duration.ofSeconds(2), Duration.ZERO));
        assertThat(afterExpiry).as("freed after lockAtMostFor").isPresent();
        afterExpiry.get().unlock();
    }

    @Test
    void lockAtLeastForKeepsAFastJobFromRunningAgainImmediately() throws Exception {
        String name = uniqueName("atleast");
        SimpleLock first =
                instance()
                        .lock(lock(name, Duration.ofMinutes(1), Duration.ofSeconds(3)))
                        .orElseThrow();
        first.unlock(); // the job finished instantly

        assertThat(instance().lock(lock(name, Duration.ofMinutes(1), Duration.ofSeconds(3))))
                .as("held until lockAtLeastFor has passed")
                .isEmpty();

        Thread.sleep(3500);

        Optional<SimpleLock> later =
                instance().lock(lock(name, Duration.ofMinutes(1), Duration.ofSeconds(3)));
        assertThat(later).isPresent();
        later.get().unlock();
    }
}
