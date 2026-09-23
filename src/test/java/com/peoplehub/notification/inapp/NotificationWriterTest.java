package com.peoplehub.notification.inapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestOrganizations;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The notification writer against real PostgreSQL: what it writes, that it commits or rolls back
 * with the caller's transaction, and that it publishes exactly one {@link NotificationCreatedEvent}
 * per row, only for a row that actually committed. Mirrors {@code EmailOutboxWriterTest}'s coverage
 * of the equivalent b1-1 writer.
 */
@IntegrationTest
@RecordApplicationEvents
class NotificationWriterTest {

    @Autowired private NotificationWriter writer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEvents events;

    private TransactionTemplate tx;
    private UUID org;
    private UUID employee;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // b2-1 (V12): notification.organization_id now has a real FK to organization(id).
        // employee_id has no FK (deliberately not retrofitted; V12's own comments).
        org = TestOrganizations.insert(jdbc);
        employee = UUID.randomUUID();
    }

    private void append(NotificationMessage message) {
        tx.executeWithoutResult(status -> writer.append(message));
    }

    private Map<String, Object> onlyRow(UUID organization) {
        return jdbc.queryForMap(
                "SELECT id, organization_id, employee_id, type, read, created_at,"
                        + " payload::text AS payload FROM notification WHERE organization_id = ?",
                organization);
    }

    @Test
    void appendsOneRowWithEveryFieldFromItsSource() {
        append(NotificationMessage.builder(org, employee, "SOMETHING_HAPPENED").build());

        Map<String, Object> row = onlyRow(org);
        assertThat(row.get("organization_id")).isEqualTo(org);
        assertThat(row.get("employee_id")).isEqualTo(employee);
        assertThat(row.get("type")).isEqualTo("SOMETHING_HAPPENED");
        assertThat(row.get("read")).isEqualTo(false);
        assertThat((Long) row.get("id")).isPositive();
    }

    @Test
    void publishesExactlyOneCreatedEventMatchingTheRow() {
        append(NotificationMessage.builder(org, employee, "SOMETHING_HAPPENED").build());

        long published =
                events.stream(NotificationCreatedEvent.class)
                        .filter(e -> e.organizationId().equals(org))
                        .count();
        assertThat(published).isEqualTo(1);

        NotificationCreatedEvent event =
                events.stream(NotificationCreatedEvent.class)
                        .filter(e -> e.organizationId().equals(org))
                        .findFirst()
                        .orElseThrow();
        assertThat(event.employeeId()).isEqualTo(employee);
        assertThat(event.type()).isEqualTo("SOMETHING_HAPPENED");
        assertThat(event.notificationId()).isEqualTo(onlyRow(org).get("id"));
    }

    // ---- transactions ----

    @Test
    void appendingOutsideATransactionIsRefused() {
        assertThatThrownBy(
                        () ->
                                writer.append(
                                        NotificationMessage.builder(
                                                        org, employee, "SOMETHING_HAPPENED")
                                                .build()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count(org)).isZero();
    }

    @Test
    void theNotificationRowCommitsWithTheBusinessChange() {
        String lockName = "business-" + UUID.randomUUID();

        tx.executeWithoutResult(
                status -> {
                    businessChange(lockName);
                    writer.append(
                            NotificationMessage.builder(org, employee, "SOMETHING_HAPPENED")
                                    .build());
                });

        assertThat(count(org)).isEqualTo(1);
        assertThat(businessRows(lockName)).isEqualTo(1);
    }

    @Test
    void rollingBackTheBusinessTransactionRemovesTheNotificationRowToo() {
        String lockName = "business-" + UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            businessChange(lockName);
                                            writer.append(
                                                    NotificationMessage.builder(
                                                                    org,
                                                                    employee,
                                                                    "SOMETHING_HAPPENED")
                                                            .build());
                                            throw new IllegalStateException("business rule failed");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(org)).as("notification rows").isZero();
        assertThat(businessRows(lockName)).as("business rows").isZero();
    }

    private void businessChange(String name) {
        jdbc.update(
                "INSERT INTO shedlock(name, lock_until, locked_at, locked_by) VALUES (?,"
                        + " timezone('utc', now()), timezone('utc', now()), 'notification-writer-test')",
                name);
    }

    private long businessRows(String name) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM shedlock WHERE name = ?", Long.class, name);
    }

    private long count(UUID organization) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE organization_id = ?",
                Long.class,
                organization);
    }
}
