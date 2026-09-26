package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.common.database.TenantContext;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestOrganizations;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox writer against real PostgreSQL: what it writes, where each value comes from, that it
 * commits or rolls back with the caller's transaction, and that concurrent enqueues are safe
 * (b1-1). Mirrors {@code AuditWriterTest}'s coverage of the equivalent b0-6 writer. Connects as the
 * container's superuser; runtime-role behaviour is {@code EmailOutboxRuntimeRoleTest}.
 */
@IntegrationTest
class EmailOutboxWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EmailOutboxWriter writer;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    // Statements that are part of the business transaction run on the application's own
    // connection, as the runtime role; the fixture connection is outside that transaction.
    @Autowired private JdbcTemplate applicationJdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private UUID org;
    private TenantContext.Scope tenant;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // b2-1 (V12): organization_id now has a real FK to organization(id).
        org = TestOrganizations.insert(jdbc);
        // b2-8 C4 (V25): the tenant an authenticated request or a job would have bound.
        tenant = TenantContext.open(org);
    }

    @AfterEach
    void closeTenant() {
        tenant.close();
    }

    private void enqueue(EmailMessage message) {
        tx.executeWithoutResult(status -> writer.enqueue(message));
    }

    private Map<String, Object> onlyRow(UUID organization) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, organization_id, recipient, type, status, attempts,"
                                + " next_attempt_at, provider_message_id, error, last_attempt_at,"
                                + " created_at, payload::text AS payload FROM email_outbox"
                                + " WHERE organization_id = ?",
                        organization);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    void enqueuesOneRowWithEveryFieldFromItsSource() {
        EmailMessage message =
                EmailMessage.builder(org, "jane@example.com", "EMPLOYEE_INVITED")
                        .payload(EmailPayload.builder().attribute("firstName", "Jane").build())
                        .build();

        enqueue(message);

        Map<String, Object> row = onlyRow(org);
        assertThat(row.get("organization_id")).isEqualTo(org);
        assertThat(row.get("recipient")).isEqualTo("jane@example.com");
        assertThat(row.get("type")).isEqualTo("EMPLOYEE_INVITED");
        assertThat((Long) row.get("id")).isPositive();
        JsonNode payload = JSON.readTree((String) row.get("payload"));
        assertThat(payload)
                .isEqualTo(JSON.readTree("{\"v\":1,\"attributes\":{\"firstName\":\"Jane\"}}"));
    }

    @Test
    void aMissingPayloadIsStoredAsJustTheVersionMarker() {
        enqueue(EmailMessage.builder(org, "jane@example.com", "EMPLOYEE_INVITED").build());

        assertThat(JSON.readTree((String) onlyRow(org).get("payload")))
                .isEqualTo(JSON.readTree("{\"v\":1}"));
    }

    // ---- b1-1 never sends: every row lands PENDING with fresh retry state ----

    @Test
    void aNewRowStartsPendingWithNoAttemptsAndNoRetryOrDeliveryState() {
        enqueue(EmailMessage.builder(org, "jane@example.com", "EMPLOYEE_INVITED").build());

        Map<String, Object> row = onlyRow(org);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("provider_message_id")).isNull();
        assertThat(row.get("error")).isNull();
        assertThat(row.get("last_attempt_at")).isNull();
    }

    // ---- transactions ----

    @Test
    void enqueuingOutsideATransactionIsRefused() {
        assertThatThrownBy(
                        () ->
                                writer.enqueue(
                                        EmailMessage.builder(
                                                        org,
                                                        "jane@example.com",
                                                        "SOMETHING_HAPPENED")
                                                .build()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count(org)).isZero();
    }

    @Test
    void theOutboxRowCommitsWithTheBusinessChange() {
        String lockName = "business-" + UUID.randomUUID();

        tx.executeWithoutResult(
                status -> {
                    businessChange(lockName);
                    writer.enqueue(
                            EmailMessage.builder(org, "jane@example.com", "SOMETHING_HAPPENED")
                                    .build());
                });

        assertThat(count(org)).isEqualTo(1);
        assertThat(businessRows(lockName)).isEqualTo(1);
    }

    @Test
    void rollingBackTheBusinessTransactionRemovesTheOutboxRowToo() {
        String lockName = "business-" + UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            businessChange(lockName);
                                            writer.enqueue(
                                                    EmailMessage.builder(
                                                                    org,
                                                                    "jane@example.com",
                                                                    "SOMETHING_HAPPENED")
                                                            .build());
                                            throw new IllegalStateException("business rule failed");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(org)).as("outbox rows").isZero();
        assertThat(businessRows(lockName)).as("business rows").isZero();
    }

    @Test
    void markingTheTransactionRollbackOnlyAlsoDiscardsTheOutboxRow() {
        tx.executeWithoutResult(
                status -> {
                    writer.enqueue(
                            EmailMessage.builder(org, "jane@example.com", "SOMETHING_HAPPENED")
                                    .build());
                    status.setRollbackOnly();
                });

        assertThat(count(org)).isZero();
    }

    /** Stands in for a business change; any table the transaction writes to would do. */
    private void businessChange(String name) {
        applicationJdbc.update(
                "INSERT INTO shedlock(name, lock_until, locked_at, locked_by) VALUES (?,"
                        + " timezone('utc', now()), timezone('utc', now()), 'outbox-writer-test')",
                name);
    }

    private long businessRows(String name) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM shedlock WHERE name = ?", Long.class, name);
    }

    // ---- time and identity ----

    @Test
    void createdAtIsTheDatabaseTransactionTimeNotTheJvmClock() {
        Boolean same =
                tx.execute(
                        status -> {
                            writer.enqueue(
                                    EmailMessage.builder(
                                                    org, "jane@example.com", "SOMETHING_HAPPENED")
                                            .build());
                            return applicationJdbc.queryForObject(
                                    "SELECT created_at = now() FROM email_outbox"
                                            + " WHERE organization_id = ?",
                                    Boolean.class,
                                    org);
                        });

        assertThat(same).isTrue();
    }

    @Test
    void theWriterNeverSuppliesTheIdOrTheTime() {
        tx.executeWithoutResult(
                status -> {
                    writer.enqueue(
                            EmailMessage.builder(org, "jane@example.com", "FIRST_THING").build());
                    writer.enqueue(
                            EmailMessage.builder(org, "jane@example.com", "SECOND_THING").build());
                });

        Map<String, Object> counts =
                jdbc.queryForMap(
                        "SELECT count(DISTINCT id) AS ids, count(DISTINCT created_at) AS times"
                                + " FROM email_outbox WHERE organization_id = ?",
                        org);
        assertThat(counts.get("ids")).isEqualTo(2L);
        assertThat(counts.get("times")).isEqualTo(1L);
    }

    // ---- concurrency ----

    @Test
    void concurrentEnqueuesAllSucceedWithDistinctIds() throws Exception {
        int writers = 16;
        int perWriter = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    // The tenant is per thread (V25): each writer binds its own.
                                    try (TenantContext.Scope scope = TenantContext.open(org)) {
                                        for (int i = 0; i < perWriter; i++) {
                                            tx.executeWithoutResult(
                                                    status ->
                                                            writer.enqueue(
                                                                    EmailMessage.builder(
                                                                                    org,
                                                                                    "jane@example.com",
                                                                                    "CONCURRENT_ENQUEUE")
                                                                            .build()));
                                        }
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> result =
                jdbc.queryForMap(
                        "SELECT count(*) AS rows, count(DISTINCT id) AS ids FROM email_outbox"
                                + " WHERE organization_id = ?",
                        org);
        assertThat(result.get("rows")).isEqualTo((long) writers * perWriter);
        assertThat(result.get("ids")).isEqualTo((long) writers * perWriter);
    }

    private long count(UUID organization) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM email_outbox WHERE organization_id = ?",
                Long.class,
                organization);
    }
}
