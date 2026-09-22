package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link EmailOutboxProcessor} against real PostgreSQL (b1-2): sending, retry/backoff, failure
 * classification, claim-before-send, concurrent claims, and stale-claim recovery. A controllable
 * clock stands in for the passage of time, the same style {@code CatchUpPatternTest} uses.
 */
@IntegrationTest
class EmailOutboxProcessorTest {

    @Autowired private JdbcClient jdbc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EmailSuppressionService suppressionService;

    private final SettableClock clock = new SettableClock();

    // @IntegrationTest does not roll back between test methods (unlike @Transactional tests), and
    // several tests here assert an exact row count / processed count: a row left over from an
    // earlier test would be silently picked up too. Start every test from an empty table. b1-4 adds
    // email_suppression to the same cleanup for the same reason: a suppression left over from an
    // earlier test would make an unrelated later test's "address is not suppressed" assumption
    // false.
    @BeforeEach
    void emptyTheOutboxAndSuppressionList() {
        jdbcTemplate.update("DELETE FROM email_outbox");
        jdbcTemplate.update("DELETE FROM email_suppression");
    }

    private EmailOutboxProcessor processor(EmailSender sender) {
        return processor(sender, Duration.ofMinutes(5));
    }

    private EmailOutboxProcessor processor(EmailSender sender, Duration staleClaimAfter) {
        return new EmailOutboxProcessor(
                jdbc,
                clock,
                sender,
                new EmailTemplateRenderer(JsonMapper.builder().build()),
                new EmailFailureClassifier(),
                new RetryPolicy(clock, List.of(Duration.ofMinutes(1), Duration.ofMinutes(5))),
                100,
                staleClaimAfter,
                suppressionService);
    }

    private long insertRow(String status, int attempts, Instant nextAttemptAt) {
        UUID org = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO email_outbox (organization_id, recipient, type, payload, status,"
                        + " attempts, next_attempt_at)"
                        + " VALUES (?, 'jane@example.com', 'EMPLOYEE_INVITED', ?::jsonb, ?, ?, ?)",
                org,
                """
                {"v":1,"attributes":{"appName":"PeopleHub","firstName":"Jane","inviteCode":"AB12CD"}}
                """,
                status,
                attempts,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM email_outbox WHERE organization_id = ?", Long.class, org);
    }

    private long insertPending() {
        return insertRow("PENDING", 0, null);
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM email_outbox WHERE id = ?", id);
    }

    // ---- happy path ----

    @Test
    void sendsAPendingRowAndMarksItSent() {
        long id = insertPending();

        int processed = processor(FakeSender.alwaysSucceeds()).run();

        assertThat(processed).isEqualTo(1);
        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("SENT");
        assertThat(after.get("attempts")).isEqualTo(1);
        assertThat(after.get("provider_message_id")).isNotNull();
        assertThat(after.get("error")).isNull();
    }

    @Test
    void aRowNotYetDueIsNotProcessed() {
        clock.set("2026-03-10T10:00:00Z");
        long id = insertRow("RETRYING", 1, Instant.parse("2026-03-10T10:05:00Z")); // 5 min from now

        int processed = processor(FakeSender.alwaysSucceeds()).run();

        assertThat(processed).isZero();
        assertThat(row(id).get("status")).isEqualTo("RETRYING");
    }

    @Test
    void aSentRowIsNeverReprocessed() {
        long id = insertPending();
        processor(FakeSender.alwaysSucceeds()).run();

        int secondRun = processor(FakeSender.thatFailsIfCalled()).run();

        assertThat(secondRun).isZero();
        assertThat(row(id).get("status")).isEqualTo("SENT");
    }

    // ---- retry / backoff ----

    @Test
    void aTransientFailureSchedulesARetryWithTheConfiguredDelay() {
        clock.set("2026-03-10T10:00:00Z");
        long id = insertPending();

        processor(FakeSender.alwaysFails(transientFailure())).run();

        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("RETRYING");
        assertThat(after.get("attempts")).isEqualTo(1);
        assertThat(((Timestamp) after.get("next_attempt_at")).toInstant())
                .isEqualTo(Instant.parse("2026-03-10T10:01:00Z")); // +PT1M, the first delay
        assertThat(after.get("error")).isEqualTo("CONNECTION_FAILED");
    }

    @Test
    void exhaustingAllRetriesMarksTheRowPermanentlyFailed() {
        clock.set("2026-03-10T10:00:00Z");
        long id = insertPending();
        EmailSender failing = FakeSender.alwaysFails(transientFailure());

        // Attempt 1 (initial): PENDING -> RETRYING (attempts=1)
        processor(failing).run();
        // Attempt 2 (1st retry, due immediately since the clock has not moved in this test)
        clock.set("2026-03-10T10:02:00Z");
        processor(failing).run();
        assertThat(row(id).get("status")).isEqualTo("RETRYING");
        assertThat(row(id).get("attempts")).isEqualTo(2);
        // Attempt 3 (2nd retry): exhausts the 2-delay schedule (3 attempts total) -> FAILED
        clock.set("2026-03-10T10:10:00Z");
        processor(failing).run();

        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("FAILED");
        assertThat(after.get("attempts")).isEqualTo(3);
    }

    // ---- permanent failures skip retries entirely ----

    @Test
    void aPermanentFailureGoesStraightToFailedWithoutUsingUpAnyRetries() {
        long id = insertPending();

        processor(FakeSender.alwaysFails(new MailAuthenticationException("bad creds"))).run();

        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("FAILED");
        assertThat(after.get("attempts")).isEqualTo(1);
        assertThat(after.get("error")).isEqualTo("AUTHENTICATION_FAILED");
        // b1-4 decision 4/5: AUTHENTICATION_FAILED is permanent but says nothing about the
        // recipient address itself -- only ADDRESS_REJECTED suppresses. See the tests below.
        assertThat(suppressionService.isSuppressed("jane@example.com")).isFalse();
    }

    // ---- b1-4: ADDRESS_REJECTED suppresses, nothing else does --------------------------------

    @Test
    void aPermanentAddressRejectedFailureSuppressesTheRecipient() throws AddressException {
        long id = insertPending();
        MailSendException addressRejected =
                new MailSendException(
                        Map.of(
                                "jane@example.com",
                                new SendFailedException(
                                        "rejected",
                                        null,
                                        new InternetAddress[0],
                                        new InternetAddress[0],
                                        new InternetAddress[] {
                                            new InternetAddress("jane@example.com")
                                        })));

        processor(FakeSender.alwaysFails(addressRejected)).run();

        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("FAILED");
        assertThat(after.get("error")).isEqualTo("ADDRESS_REJECTED");
        assertThat(suppressionService.isSuppressed("jane@example.com")).isTrue();
    }

    @Test
    void aTransientFailureThatExhaustsRetriesDoesNotSuppressTheRecipient() {
        clock.set("2026-03-10T10:00:00Z");
        long id = insertPending();
        EmailSender alwaysConnectionFailure =
                FakeSender.alwaysFails(new MailSendException("could not connect"));

        processor(alwaysConnectionFailure).run(); // attempt 1 -> RETRYING
        clock.set("2026-03-10T10:02:00Z");
        processor(alwaysConnectionFailure).run(); // attempt 2 -> RETRYING
        clock.set("2026-03-10T10:10:00Z");
        processor(alwaysConnectionFailure).run(); // attempt 3 -> exhausted -> FAILED

        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("FAILED");
        assertThat(after.get("error")).isEqualTo("CONNECTION_FAILED");
        // Transient, even though it eventually exhausted its retries: must never suppress.
        assertThat(suppressionService.isSuppressed("jane@example.com")).isFalse();
    }

    @Test
    void aSuppressedRecipientIsSkippedBeforeRenderingOrSending() {
        suppressionService.suppress("jane@example.com", SuppressionReason.BOUNCE);
        long id = insertPending();

        int processed = processor(FakeSender.thatFailsIfCalled()).run();

        assertThat(processed).isEqualTo(1); // claimed and terminated, just never sent
        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("FAILED");
        assertThat(after.get("attempts")).isEqualTo(0); // never actually attempted
        assertThat(after.get("error")).isEqualTo("SUPPRESSED");
    }

    @Test
    void aTemplateFailureGoesStraightToFailedAndNeverCallsTheSender() {
        UUID org = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO email_outbox (organization_id, recipient, type, payload)"
                        + " VALUES (?, 'jane@example.com', 'NO_SUCH_TEMPLATE', '{}'::jsonb)",
                org);
        long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM email_outbox WHERE organization_id = ?", Long.class, org);

        processor(FakeSender.thatFailsIfCalled()).run();

        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("FAILED");
        assertThat(after.get("attempts")).isEqualTo(0); // the send was never attempted
        assertThat(after.get("error")).isEqualTo("TEMPLATE_ERROR");
    }

    @Test
    void aMissingFromAddressGoesStraightToFailedAsAConfigurationError() {
        long id = insertPending();
        EmailSender unconfigured =
                (recipient, subject, body) -> {
                    throw new EmailConfigurationException("no from-address");
                };

        processor(unconfigured).run();

        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo("FAILED");
        assertThat(after.get("error")).isEqualTo("CONFIGURATION_ERROR");
    }

    // ---- claim before send, batch size ----

    @Test
    void batchSizeLimitsHowManyRowsOneRunProcesses() {
        for (int i = 0; i < 5; i++) {
            insertPending();
        }
        EmailOutboxProcessor small =
                new EmailOutboxProcessor(
                        jdbc,
                        clock,
                        FakeSender.alwaysSucceeds(),
                        new EmailTemplateRenderer(JsonMapper.builder().build()),
                        new EmailFailureClassifier(),
                        new RetryPolicy(clock, List.of(Duration.ofMinutes(1))),
                        2, // batch size
                        Duration.ofMinutes(5),
                        suppressionService);

        int processed = small.run();

        assertThat(processed).isEqualTo(2);
        Integer stillPending =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM email_outbox WHERE status = 'PENDING'",
                        Integer.class);
        assertThat(stillPending).isEqualTo(3);
    }

    @Test
    void concurrentRunsClaimEachRowExactlyOnce() throws Exception {
        int rows = 20;
        for (int i = 0; i < rows; i++) {
            insertPending();
        }
        EmailSender sender = FakeSender.alwaysSucceeds();
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> awaitAndRun(together, processor(sender)));
            Future<Integer> b = pool.submit(() -> awaitAndRun(together, processor(sender)));

            assertThat(a.get() + b.get())
                    .as("every row claimed exactly once across both runs")
                    .isEqualTo(rows);
        } finally {
            pool.shutdownNow();
        }
        Integer sent =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM email_outbox WHERE status = 'SENT'", Integer.class);
        assertThat(sent).isEqualTo(rows);
    }

    private static int awaitAndRun(CyclicBarrier barrier, EmailOutboxProcessor processor)
            throws Exception {
        barrier.await();
        return processor.run();
    }

    // ---- stale-claim recovery (a row stuck in SENDING because the process crashed mid-send) ----

    @Test
    void aStaleSendingClaimIsReclaimedAndProcessedOnTheNextRun() {
        clock.set("2026-03-10T10:00:00Z");
        // Looks exactly like a row a previous, now-dead instance claimed and never finished.
        long id = insertRowInStatus("SENDING", "2026-03-10T09:50:00Z"); // 10 minutes ago

        int processed = processor(FakeSender.alwaysSucceeds(), Duration.ofMinutes(5)).run();

        assertThat(processed).isEqualTo(1);
        assertThat(row(id).get("status")).isEqualTo("SENT");
    }

    @Test
    void aRecentSendingClaimIsLeftAloneNotYetStale() {
        clock.set("2026-03-10T10:00:00Z");
        long id =
                insertRowInStatus("SENDING", "2026-03-10T09:59:00Z"); // 1 minute ago, not stale yet

        int processed = processor(FakeSender.alwaysSucceeds(), Duration.ofMinutes(5)).run();

        assertThat(processed).isZero();
        assertThat(row(id).get("status")).isEqualTo("SENDING");
    }

    private long insertRowInStatus(String status, String lastAttemptAt) {
        UUID org = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO email_outbox (organization_id, recipient, type, payload, status,"
                        + " last_attempt_at)"
                        + " VALUES (?, 'jane@example.com', 'EMPLOYEE_INVITED', ?::jsonb, ?, ?)",
                org,
                """
                {"v":1,"attributes":{"appName":"PeopleHub","firstName":"Jane","inviteCode":"AB12CD"}}
                """,
                status,
                Timestamp.from(Instant.parse(lastAttemptAt)));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM email_outbox WHERE organization_id = ?", Long.class, org);
    }

    private static MailSendException transientFailure() {
        return new MailSendException("could not connect");
    }

    /** A controllable {@link EmailSender} for tests; never makes a real network call. */
    private interface FakeSender {
        static EmailSender alwaysSucceeds() {
            return (recipient, subject, body) -> UUID.randomUUID().toString();
        }

        static EmailSender alwaysFails(RuntimeException failure) {
            return (recipient, subject, body) -> {
                throw failure;
            };
        }

        static EmailSender thatFailsIfCalled() {
            return (recipient, subject, body) -> {
                throw new AssertionError("EmailSender should not have been called");
            };
        }
    }

    /** A clock the test moves by hand, the same shape as {@code CatchUpPatternTest}'s. */
    private static final class SettableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void set(String instant) {
            this.now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
