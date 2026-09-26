package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V5 (email outbox sending): applies cleanly, and the partial index it adds has the approved shape
 * (b1-2). Mirrors {@code EmailOutboxMigrationTest}'s coverage of the equivalent V4 migration.
 * Runtime role behaviour for the columns V5 grants is {@code EmailOutboxRuntimeRoleTest}.
 */
@IntegrationTest
class EmailOutboxSendingMigrationTest {

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    @Test
    void v5AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND success",
                        Integer.class);
        Integer failed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(failed).isZero();
    }

    @Test
    void theDueIndexExistsIsPartialAndCoversNextAttemptAt() {
        Map<String, Object> index =
                jdbc.queryForMap(
                        "SELECT indexdef FROM pg_indexes"
                                + " WHERE tablename = 'email_outbox' AND indexname ="
                                + " 'idx_email_outbox_due'");
        String definition = (String) index.get("indexdef");

        assertThat(definition).contains("next_attempt_at");
        // A partial index: only rows the processor's due-row scan cares about are indexed.
        assertThat(definition)
                .containsIgnoringCase("WHERE")
                .contains("PENDING")
                .contains("RETRYING");
    }

    @Test
    void theDueIndexIsTheOnlyNewIndexAddedByV5() {
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'email_outbox'",
                        String.class);

        assertThat(indexes).containsExactlyInAnyOrder("pk_email_outbox", "idx_email_outbox_due");
    }
}
