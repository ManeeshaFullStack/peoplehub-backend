package com.peoplehub.common.scheduling.testsupport;

import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestDatabaseRoles;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test fixture for {@link ProbeJob}'s scratch table. The table is test scaffolding, not application
 * schema: it lives in its own schema (so Flyway never finds a non-empty {@code public} schema) and
 * is created through a privileged fixture connection, because the runtime role the job runs as
 * cannot create tables. The runtime role then gets exactly what {@link ProbeJob} does with it and
 * nothing more: insert a run's instance and start time and read back its id, then set its end time
 * by id. It cannot delete or truncate, and it never sees the privileged connection.
 *
 * <p>A test using the shared context imports this configuration; a test that starts its own
 * application calls {@link #provision} with its own superuser connection before starting it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ProbeJobSchema {

    public static final String SCHEMA = "scheduler_probe";
    public static final String TABLE = SCHEMA + ".job_probe";

    private static final String RUNTIME_ROLE = TestDatabaseRoles.RUNTIME_ROLE;

    private static final List<String> STATEMENTS =
            List.of(
                    "CREATE SCHEMA IF NOT EXISTS " + SCHEMA,
                    "CREATE TABLE IF NOT EXISTS "
                            + TABLE
                            + " (id BIGSERIAL PRIMARY KEY, instance TEXT NOT NULL,"
                            + " started_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ)",
                    "GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE,
                    // INSERT ... RETURNING id, and UPDATE ... WHERE id = ?, both read id.
                    "GRANT SELECT (id) ON " + TABLE + " TO " + RUNTIME_ROLE,
                    "GRANT INSERT (instance, started_at) ON " + TABLE + " TO " + RUNTIME_ROLE,
                    "GRANT UPDATE (ended_at) ON " + TABLE + " TO " + RUNTIME_ROLE,
                    // The id column's default is nextval() on this sequence.
                    "GRANT USAGE ON SEQUENCE " + SCHEMA + ".job_probe_id_seq TO " + RUNTIME_ROLE);

    /** Creates the schema and table if missing and grants the runtime role; idempotent. */
    public static void provision(JdbcTemplate privileged) {
        STATEMENTS.forEach(privileged::execute);
    }

    /**
     * Provisions the table while the shared context starts. Singletons are all created before the
     * scheduler starts (it starts when the context is refreshed), so the table exists before the
     * job's first run.
     */
    @Bean
    Provisioned probeJobSchemaProvisioned(@PrivilegedFixture JdbcTemplate privileged) {
        provision(privileged);
        return new Provisioned();
    }

    /** Marker bean: the scratch table has been provisioned. */
    public static final class Provisioned {}
}
