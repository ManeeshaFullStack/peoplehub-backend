package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class FlywayMigrationTest {

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;

    @Test
    void migrationsApplyCleanlyToAnEmptyDatabase() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
                        Integer.class);
        Integer failed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(failed).isZero();
    }

    @Test
    void btreeGistExtensionIsInstalled() {
        Integer installed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'",
                        Integer.class);

        assertThat(installed).isEqualTo(1);
    }

    @Test
    void flywayCleanIsDisabled() {
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class);
    }
}
