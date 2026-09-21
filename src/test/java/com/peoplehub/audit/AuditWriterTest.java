package com.peoplehub.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.support.IntegrationTest;
import java.net.Inet6Address;
import java.net.InetAddress;
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
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit writer against real PostgreSQL: what it writes, where each value comes from, that it
 * commits or rolls back with the caller's transaction, and that concurrent appends are safe (B0-6).
 * Connects as the container's superuser; {@code TwoRoleWiringTest} runs the writer as the
 * least-privileged runtime role.
 */
@IntegrationTest
class AuditWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private AuditWriter writer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private UUID org;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        org = UUID.randomUUID();
        ActorId.set("job:audit-writer-test");
        MDC.remove(CorrelationId.MDC_KEY);
    }

    @AfterEach
    void clearContext() {
        ActorId.clear();
        MDC.remove(CorrelationId.MDC_KEY);
    }

    private void append(AuditEvent event) {
        tx.executeWithoutResult(status -> writer.append(event));
    }

    private Map<String, Object> onlyRow(UUID organization) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, organization_id, actor_id, action, target_type, target_id,"
                                + " occurred_at, host(ip) AS ip, correlation_id, details::text AS"
                                + " details FROM audit_log WHERE organization_id = ?",
                        organization);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    void appendsOneRowWithEveryFieldFromItsSource() throws Exception {
        MDC.put(CorrelationId.MDC_KEY, "req-42");
        AuditEvent event =
                AuditEvent.builder(org, "EMPLOYEE_ROLE_CHANGED")
                        .target(AuditTarget.of("EMPLOYEE", "e-7"))
                        .ip(InetAddress.getByName("203.0.113.7"))
                        .details(
                                AuditDetails.builder()
                                        .attribute("reasonCode", "PROMOTION")
                                        .change("role", "EMPLOYEE", "ADMIN")
                                        .build())
                        .build();

        append(event);

        Map<String, Object> row = onlyRow(org);
        assertThat(row.get("organization_id")).isEqualTo(org);
        assertThat(row.get("actor_id")).isEqualTo("job:audit-writer-test");
        assertThat(row.get("action")).isEqualTo("EMPLOYEE_ROLE_CHANGED");
        assertThat(row.get("target_type")).isEqualTo("EMPLOYEE");
        assertThat(row.get("target_id")).isEqualTo("e-7");
        assertThat(row.get("ip")).isEqualTo("203.0.113.7");
        assertThat(row.get("correlation_id")).isEqualTo("req-42");
        assertThat((Long) row.get("id")).isPositive();
        JsonNode details = JSON.readTree((String) row.get("details"));
        assertThat(details)
                .isEqualTo(
                        JSON.readTree(
                                """
                                {"v":1,
                                 "attributes":{"reasonCode":"PROMOTION"},
                                 "changes":[{"field":"role","before":"EMPLOYEE","after":"ADMIN"}]}
                                """));
    }

    @Test
    void optionalPartsAreStoredAsNull() {
        append(AuditEvent.builder(org, "EXPORT_STARTED").build());

        Map<String, Object> row = onlyRow(org);
        assertThat(row.get("target_type")).isNull();
        assertThat(row.get("target_id")).isNull();
        assertThat(row.get("ip")).isNull();
        assertThat(row.get("correlation_id")).isNull();
        assertThat(JSON.readTree((String) row.get("details")))
                .isEqualTo(JSON.readTree("{\"v\":1}"));
    }

    @Test
    void aTargetWithoutAnIdIsStoredWithTypeOnly() {
        append(
                AuditEvent.builder(org, "EXPORT_STARTED")
                        .target(AuditTarget.ofType("EMPLOYEE_LIST"))
                        .build());

        Map<String, Object> row = onlyRow(org);
        assertThat(row.get("target_type")).isEqualTo("EMPLOYEE_LIST");
        assertThat(row.get("target_id")).isNull();
    }

    @Test
    void anIpv6AddressWithAScopeIdIsStoredWithoutIt() throws Exception {
        byte[] loopbackLinkLocal = InetAddress.getByName("fe80::1").getAddress();
        InetAddress scoped = Inet6Address.getByAddress(null, loopbackLinkLocal, 1);
        assertThat(scoped.getHostAddress()).contains("%");

        append(AuditEvent.builder(org, "SESSION_OPENED").ip(scoped).build());

        assertThat(onlyRow(org).get("ip")).isEqualTo("fe80::1");
    }

    // ---- where the actor and the correlation id come from ----

    @Test
    void theActorIsWhoeverTheRequestOrJobContextSays() {
        for (String actor :
                List.of("anonymous", "SYSTEM", "job:daily-close", UUID.randomUUID().toString())) {
            UUID organization = UUID.randomUUID();
            ActorId.set(actor);

            append(AuditEvent.builder(organization, "SOMETHING_HAPPENED").build());

            assertThat(onlyRow(organization).get("actor_id")).isEqualTo(actor);
        }
    }

    @Test
    void withoutAnActorNothingIsWrittenAndTheCallerIsTold() {
        ActorId.clear();

        assertThatThrownBy(() -> append(AuditEvent.builder(org, "SOMETHING_HAPPENED").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actor");

        assertThat(count(org)).isZero();
    }

    @Test
    void theDatabaseStillRejectsAnActorThatBypassedActorIdValidation() {
        // MDC.put skips ActorId.set's check; the table's check constraint is the backstop.
        MDC.put(ActorId.MDC_KEY, "not a valid actor");

        assertThatThrownBy(() -> append(AuditEvent.builder(org, "SOMETHING_HAPPENED").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count(org)).isZero();
    }

    // ---- transactions ----

    @Test
    void appendingOutsideATransactionIsRefused() {
        assertThatThrownBy(
                        () -> writer.append(AuditEvent.builder(org, "SOMETHING_HAPPENED").build()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count(org)).isZero();
    }

    @Test
    void theAuditRowCommitsWithTheBusinessChange() {
        String lockName = "business-" + UUID.randomUUID();

        tx.executeWithoutResult(
                status -> {
                    businessChange(lockName);
                    writer.append(AuditEvent.builder(org, "SOMETHING_HAPPENED").build());
                });

        assertThat(count(org)).isEqualTo(1);
        assertThat(businessRows(lockName)).isEqualTo(1);
    }

    @Test
    void rollingBackTheBusinessTransactionRemovesTheAuditRowToo() {
        String lockName = "business-" + UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            businessChange(lockName);
                                            writer.append(
                                                    AuditEvent.builder(org, "SOMETHING_HAPPENED")
                                                            .build());
                                            throw new IllegalStateException("business rule failed");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(org)).as("audit rows").isZero();
        assertThat(businessRows(lockName)).as("business rows").isZero();
    }

    @Test
    void markingTheTransactionRollbackOnlyAlsoDiscardsTheAuditRow() {
        tx.executeWithoutResult(
                status -> {
                    writer.append(AuditEvent.builder(org, "SOMETHING_HAPPENED").build());
                    status.setRollbackOnly();
                });

        assertThat(count(org)).isZero();
    }

    /** Stands in for a business change; any table the transaction writes to would do. */
    private void businessChange(String name) {
        jdbc.update(
                "INSERT INTO shedlock(name, lock_until, locked_at, locked_by) VALUES (?,"
                        + " timezone('utc', now()), timezone('utc', now()), 'audit-writer-test')",
                name);
    }

    private long businessRows(String name) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM shedlock WHERE name = ?", Long.class, name);
    }

    // ---- time ----

    @Test
    void occurredAtIsTheDatabaseTransactionTimeNotTheJvmClock() {
        Boolean same =
                tx.execute(
                        status -> {
                            writer.append(AuditEvent.builder(org, "SOMETHING_HAPPENED").build());
                            // now() is the transaction start time: the row must carry exactly it.
                            return jdbc.queryForObject(
                                    "SELECT occurred_at = now() FROM audit_log"
                                            + " WHERE organization_id = ?",
                                    Boolean.class,
                                    org);
                        });

        assertThat(same).isTrue();
    }

    @Test
    void theWriterNeverSuppliesTheIdOrTheTime() {
        // Two rows in one transaction: the same database time, distinct generated ids.
        tx.executeWithoutResult(
                status -> {
                    writer.append(AuditEvent.builder(org, "FIRST_THING").build());
                    writer.append(AuditEvent.builder(org, "SECOND_THING").build());
                });

        Map<String, Object> counts =
                jdbc.queryForMap(
                        "SELECT count(DISTINCT id) AS ids, count(DISTINCT occurred_at) AS times"
                                + " FROM audit_log WHERE organization_id = ?",
                        org);
        assertThat(counts.get("ids")).isEqualTo(2L);
        assertThat(counts.get("times")).isEqualTo(1L);
    }

    // ---- concurrency ----

    @Test
    void concurrentAppendsAllSucceedWithDistinctIds() throws Exception {
        int writers = 16;
        int perWriter = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                String actor = "job:writer-" + w;
                futures.add(
                        pool.submit(
                                () -> {
                                    // MDC is per thread: each writer is its own actor.
                                    ActorId.set(actor);
                                    try {
                                        start.await();
                                        for (int i = 0; i < perWriter; i++) {
                                            int n = i;
                                            tx.executeWithoutResult(
                                                    status ->
                                                            writer.append(
                                                                    AuditEvent.builder(
                                                                                    org,
                                                                                    "CONCURRENT_APPEND")
                                                                            .details(
                                                                                    AuditDetails
                                                                                            .builder()
                                                                                            .attribute(
                                                                                                    "n",
                                                                                                    n)
                                                                                            .build())
                                                                            .build()));
                                        }
                                        return null;
                                    } finally {
                                        ActorId.clear();
                                    }
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
                        "SELECT count(*) AS rows, count(DISTINCT id) AS ids,"
                                + " count(DISTINCT actor_id) AS actors FROM audit_log"
                                + " WHERE organization_id = ?",
                        org);
        assertThat(result.get("rows")).isEqualTo((long) writers * perWriter);
        assertThat(result.get("ids")).isEqualTo((long) writers * perWriter);
        assertThat(result.get("actors")).isEqualTo((long) writers);
    }

    private long count(UUID organization) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ?",
                Long.class,
                organization);
    }
}
