package com.peoplehub.support;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Records, for every row the application writes to a tenant table, the row's organization and the
 * transaction's tenant setting ({@code peoplehub.organization_id}) at that moment (b2-8 C3). Before
 * row-level security is on (V25), this is how a test proves that a flow bound its transaction to
 * the right organization before writing: every write must carry its own organization.
 *
 * <p>Test-only: a trigger on each watched table, installed and removed through the privileged
 * fixture connection. The trigger function is {@code SECURITY DEFINER}, so the runtime role needs
 * no privilege on the probe's own table; {@code session_user} still names the role that wrote.
 * Install it in {@code @BeforeEach} and remove it in {@code @AfterEach}: the shared context's
 * database outlives the test.
 */
public final class TenantWriteProbe {

    /** Tables a public flow may write. */
    public static final List<String> WATCHED =
            List.of(
                    "organization",
                    "employee",
                    "employee_invitation",
                    "refresh_token",
                    "organization_verification_token",
                    "password_reset_token",
                    "mfa_challenge",
                    "audit_log",
                    "email_outbox");

    private final JdbcTemplate privileged;

    /** {@code privileged} is the {@link PrivilegedFixture} template; never the application's. */
    public TenantWriteProbe(JdbcTemplate privileged) {
        this.privileged = privileged;
    }

    public void install() {
        privileged.execute("CREATE SCHEMA IF NOT EXISTS c3_probe");
        privileged.execute(
                "CREATE TABLE IF NOT EXISTS c3_probe.write (id bigserial PRIMARY KEY,"
                        + " tbl text NOT NULL, op text NOT NULL, row_org uuid, bound text,"
                        + " who text NOT NULL)");
        privileged.execute("TRUNCATE c3_probe.write");
        privileged.execute(
                """
                CREATE OR REPLACE FUNCTION c3_probe.record() RETURNS trigger
                    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
                BEGIN
                    INSERT INTO c3_probe.write (tbl, op, row_org, bound, who) VALUES (
                        TG_TABLE_NAME, TG_OP,
                        CASE WHEN TG_TABLE_NAME = 'organization'
                             THEN (to_jsonb(NEW) ->> 'id')::uuid
                             ELSE (to_jsonb(NEW) ->> 'organization_id')::uuid END,
                        current_setting('peoplehub.organization_id', true),
                        session_user);
                    RETURN NULL;
                END $$
                """);
        for (String table : WATCHED) {
            privileged.execute("DROP TRIGGER IF EXISTS c3_probe_write ON public." + table);
            privileged.execute(
                    "CREATE TRIGGER c3_probe_write AFTER INSERT OR UPDATE ON public."
                            + table
                            + " FOR EACH ROW EXECUTE FUNCTION c3_probe.record()");
        }
    }

    public void remove() {
        for (String table : WATCHED) {
            privileged.execute("DROP TRIGGER IF EXISTS c3_probe_write ON public." + table);
        }
        privileged.execute("DROP SCHEMA IF EXISTS c3_probe CASCADE");
    }

    /**
     * Writes by the application (the runtime role) whose transaction was not bound to the row's
     * organization, as "table OP", in order.
     */
    public List<String> unboundWrites() {
        return privileged.queryForList(
                "SELECT tbl || ' ' || op FROM c3_probe.write WHERE who = ?"
                        + " AND bound IS DISTINCT FROM row_org::text ORDER BY id",
                String.class,
                TestDatabaseRoles.RUNTIME_ROLE);
    }

    /** Each "table OP" the application wrote for {@code organizationId} while bound to it. */
    public Set<String> boundWrites(UUID organizationId) {
        return privileged
                .queryForList(
                        "SELECT tbl || ' ' || op FROM c3_probe.write WHERE who = ?"
                                + " AND row_org = ? AND bound = row_org::text",
                        String.class,
                        TestDatabaseRoles.RUNTIME_ROLE,
                        organizationId)
                .stream()
                .collect(Collectors.toSet());
    }

    /** How many tenant rows the application wrote at all. */
    public long applicationWrites() {
        return privileged.queryForObject(
                "SELECT count(*) FROM c3_probe.write WHERE who = ?",
                Long.class,
                TestDatabaseRoles.RUNTIME_ROLE);
    }
}
