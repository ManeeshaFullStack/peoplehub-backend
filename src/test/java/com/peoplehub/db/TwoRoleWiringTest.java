package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.PeopleHubApplication;
import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.database.TenantContext;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestDatabaseRoles;
import com.peoplehub.support.TestOrganizations;
import com.peoplehub.support.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Flyway and the application use different credentials (B0-6/3): migrations run as the owner role
 * ({@code SPRING_FLYWAY_USER}), the application as the least-privileged runtime role ({@code
 * SPRING_DATASOURCE_USERNAME}). The whole application is started the way a deployment would start
 * it, against a database that has only been provisioned with roles, so this proves the wiring, the
 * ownership, the privilege boundary through the application's own connections, and how a bad or
 * missing runtime role name fails.
 *
 * <p>Applications are started programmatically, like {@code ScheduledJobInstancesTest}, because the
 * Spring test framework owns and reuses its context and this needs a database whose first migration
 * is run by the owner role.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TwoRoleWiringTest {

    static final PostgreSQLContainer POSTGRES = TestcontainersConfiguration.newPostgresContainer();
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(TestcontainersConfiguration.REDIS_IMAGE))
                    .withExposedPorts(6379);

    private static ConfigurableApplicationContext app;

    @BeforeAll
    static void startContainers() {
        POSTGRES.start();
        REDIS.start();
    }

    @AfterAll
    static void stopEverything() {
        if (app != null) {
            app.close();
        }
        REDIS.stop();
        POSTGRES.stop();
    }

    private static List<String> arguments(
            PostgreSQLContainer postgres, String runtimeUser, String flywayUser, String... more) {
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");
        args.add("--spring.datasource.url=" + postgres.getJdbcUrl());
        args.add("--spring.datasource.username=" + runtimeUser);
        args.add("--spring.datasource.password=" + passwordOf(runtimeUser, postgres));
        if (flywayUser != null) {
            args.add("--spring.flyway.user=" + flywayUser);
            args.add("--spring.flyway.password=" + passwordOf(flywayUser, postgres));
        }
        args.add("--spring.data.redis.host=" + REDIS.getHost());
        args.add("--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        args.addAll(List.of(more));
        return args;
    }

    private static String passwordOf(String user, PostgreSQLContainer postgres) {
        return switch (user) {
            case TestDatabaseRoles.RUNTIME_ROLE -> TestDatabaseRoles.RUNTIME_PASSWORD;
            case TestDatabaseRoles.OWNER_ROLE -> TestDatabaseRoles.OWNER_PASSWORD;
            default -> postgres.getPassword();
        };
    }

    private static ConfigurableApplicationContext start(List<String> args) {
        return new SpringApplicationBuilder(PeopleHubApplication.class)
                .run(args.toArray(String[]::new));
    }

    private static synchronized ConfigurableApplicationContext app() {
        if (app == null) {
            app =
                    start(
                            arguments(
                                    POSTGRES,
                                    TestDatabaseRoles.RUNTIME_ROLE,
                                    TestDatabaseRoles.OWNER_ROLE));
        }
        return app;
    }

    private static Connection superuser(PostgreSQLContainer postgres) throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String messagesOf(Throwable t) {
        StringBuilder all = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            all.append(c.getMessage()).append('\n');
        }
        return all.toString();
    }

    // ---- the runtime role name is validated before Flyway touches the database ----

    @Test
    @Order(1)
    void anUnsafeRuntimeRoleNameStopsStartupBeforeAnyMigrationRuns() throws Exception {
        for (String unsafe :
                List.of(
                        "Peoplehub_App",
                        "app;DROP TABLE shedlock",
                        "app'--",
                        "app\"x",
                        "peoplehub-app",
                        "app name",
                        "a".repeat(64))) {
            assertThatThrownBy(
                            () ->
                                    start(
                                            arguments(
                                                    POSTGRES,
                                                    POSTGRES.getUsername(),
                                                    null,
                                                    "--PEOPLEHUB_DB_RUNTIME_ROLE=" + unsafe)))
                    .as("role name '" + unsafe + "'")
                    .satisfies(e -> assertThat(messagesOf(e)).contains("runtime_role"));
        }

        // Not one statement of any migration ran: there is not even a Flyway history table.
        try (Connection c = superuser(POSTGRES)) {
            assertThat(scalar(c, "SELECT to_regclass('flyway_schema_history')")).isNull();
            assertThat(scalar(c, "SELECT to_regclass('audit_log')")).isNull();
        }
    }

    @Test
    @Order(2)
    void aMissingRuntimeRoleFailsTheMigrationWithAClearMessageAndLeavesNoAuditTable()
            throws Exception {
        // Its own database: the failed V3 must not touch the one the wiring test migrates as owner.
        PostgreSQLContainer other = TestcontainersConfiguration.newPostgresContainer();
        other.start();
        try {
            assertThatThrownBy(
                            () ->
                                    start(
                                            arguments(
                                                    other,
                                                    other.getUsername(),
                                                    null,
                                                    "--PEOPLEHUB_DB_RUNTIME_ROLE=no_such_role")))
                    .satisfies(
                            e ->
                                    assertThat(messagesOf(e))
                                            .contains("no_such_role")
                                            .contains("does not exist")
                                            .contains("Provision the database roles"));

            try (Connection c = superuser(other)) {
                assertThat(scalar(c, "SELECT to_regclass('audit_log')")).isNull();
                assertThat(
                                scalar(
                                        c,
                                        "SELECT count(*) FROM flyway_schema_history WHERE version"
                                                + " = '3'"))
                        .isEqualTo("0");
            }
        } finally {
            other.stop();
        }
    }

    // ---- Flyway as owner, the application as the runtime role ----

    @Test
    @Order(3)
    void migrationsRunAsTheOwnerRoleAndTheApplicationConnectsAsTheRuntimeRole() throws Exception {
        ConfigurableApplicationContext context = app();
        JdbcTemplate appJdbc = new JdbcTemplate(context.getBean(DataSource.class));

        assertThat(appJdbc.queryForObject("SELECT current_user", String.class))
                .isEqualTo(TestDatabaseRoles.RUNTIME_ROLE);

        try (Connection c = superuser(POSTGRES)) {
            assertThat(
                            scalar(
                                    c,
                                    "SELECT string_agg(DISTINCT installed_by, ',') FROM"
                                            + " flyway_schema_history"))
                    .as("who ran the migrations")
                    .isEqualTo(TestDatabaseRoles.OWNER_ROLE);
            assertThat(scalar(c, "SELECT count(*) FROM flyway_schema_history WHERE success"))
                    .isEqualTo("25");
            for (String table :
                    List.of("audit_log", "shedlock", "email_outbox", "flyway_schema_history")) {
                assertThat(
                                scalar(
                                        c,
                                        "SELECT tableowner FROM pg_tables WHERE tablename = '"
                                                + table
                                                + "'"))
                        .as("owner of " + table)
                        .isEqualTo(TestDatabaseRoles.OWNER_ROLE);
            }
            assertThat(
                            scalar(
                                    c,
                                    "SELECT rolsuper FROM pg_roles WHERE rolname = '"
                                            + TestDatabaseRoles.OWNER_ROLE
                                            + "'"))
                    .as("the owner role is not a superuser")
                    .isEqualTo("f");
        }
    }

    @Test
    @Order(4)
    void theWriterAppendsAsTheRuntimeRoleAndRollsBackWithTheCallersTransaction() {
        ConfigurableApplicationContext context = app();
        AuditWriter writer = context.getBean(AuditWriter.class);
        JdbcTemplate appJdbc = new JdbcTemplate(context.getBean(DataSource.class));
        TransactionTemplate tx =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        // b2-1 (V12): audit_log.organization_id has a real FK. Since b2-8 C4 (V25) the runtime role
        // can no longer insert organizations directly, so the fixture rows come from the superuser;
        // everything the application does still runs as the runtime role, under each tenant.
        JdbcTemplate fixture = TestDatabaseRoles.privilegedFixtureJdbc(POSTGRES);
        UUID org = TestOrganizations.insert(fixture);
        UUID rolledBack = TestOrganizations.insert(fixture);
        ActorId.set("job:two-role-test");
        try {
            try (TenantContext.Scope tenant = TenantContext.open(org)) {
                tx.executeWithoutResult(
                        status ->
                                writer.append(
                                        AuditEvent.builder(org, "SOMETHING_HAPPENED")
                                                .target(AuditTarget.of("EMPLOYEE", "e-1"))
                                                .details(
                                                        AuditDetails.builder()
                                                                .change("role", "EMPLOYEE", "ADMIN")
                                                                .build())
                                                .build()));
            }
            try (TenantContext.Scope tenant = TenantContext.open(rolledBack)) {
                tx.executeWithoutResult(
                        status -> {
                            writer.append(
                                    AuditEvent.builder(rolledBack, "SOMETHING_HAPPENED").build());
                            status.setRollbackOnly();
                        });
            }
        } finally {
            ActorId.clear();
        }

        Map<String, Object> row =
                fixture.queryForMap(
                        "SELECT actor_id, action, occurred_at IS NOT NULL AS timed, id > 0 AS ided"
                                + " FROM audit_log WHERE organization_id = ?",
                        org);
        assertThat(row.get("actor_id")).isEqualTo("job:two-role-test");
        assertThat(row.get("action")).isEqualTo("SOMETHING_HAPPENED");
        assertThat(row.get("timed")).isEqualTo(true);
        assertThat(row.get("ided")).isEqualTo(true);
        assertThat(
                        fixture.queryForObject(
                                "SELECT count(*) FROM audit_log WHERE organization_id = ?",
                                Long.class,
                                rolledBack))
                .isZero();
    }

    @Test
    @Order(5)
    void theApplicationsOwnConnectionsCannotUpdateDeleteOrTruncate() {
        ConfigurableApplicationContext context = app();
        JdbcTemplate appJdbc = new JdbcTemplate(context.getBean(DataSource.class));
        TransactionTemplate tx =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        UUID org = TestOrganizations.insert(TestDatabaseRoles.privilegedFixtureJdbc(POSTGRES));

        // Inside a tenant-bound transaction, as the application always runs (b2-8 C4, V25), so
        // what stops these is the missing privilege, not the missing tenant.
        for (String sql :
                List.of(
                        "UPDATE audit_log SET action = 'TAMPERED'",
                        "DELETE FROM audit_log",
                        "TRUNCATE audit_log")) {
            assertThatThrownBy(
                            () -> {
                                try (TenantContext.Scope tenant = TenantContext.open(org)) {
                                    tx.executeWithoutResult(status -> appJdbc.execute(sql));
                                }
                            })
                    .as(sql)
                    .satisfies(
                            e ->
                                    assertThat(SqlErrors.sqlState(e))
                                            .isEqualTo(SqlErrors.INSUFFICIENT_PRIVILEGE));
        }
    }

    @Test
    @Order(6)
    void theOwnerRoleIsHeldBackByTheTriggerNotOnlyByPrivileges() throws Exception {
        // The owner has every privilege on its own table, so what stops it is the trigger.
        try (Connection owner = TestDatabaseRoles.ownerConnection(POSTGRES);
                Statement s = owner.createStatement()) {
            assertThat(scalar(owner, "SELECT count(*) FROM audit_log")).isNotEqualTo("0");
            for (String[] attempt :
                    new String[][] {
                        {"UPDATE audit_log SET action = 'TAMPERED'", "UPDATE"},
                        {"DELETE FROM audit_log", "DELETE"},
                        {"TRUNCATE audit_log", "TRUNCATE"}
                    }) {
                assertThatThrownBy(() -> s.execute(attempt[0]))
                        .as(attempt[0])
                        .satisfies(
                                e ->
                                        assertThat(SqlErrors.sqlState(e))
                                                .isEqualTo(SqlErrors.RAISED_EXCEPTION))
                        .satisfies(
                                e ->
                                        assertThat(SqlErrors.sqlMessage(e))
                                                .contains(
                                                        "audit_log is append-only: " + attempt[1]));
            }
        }
    }

    @Test
    @Order(7)
    void scheduledJobLocksWorkThroughTheApplicationsOwnLockProvider() {
        ConfigurableApplicationContext context = app();
        LockProvider provider = context.getBean(LockProvider.class);
        Clock clock = context.getBean(Clock.class);
        String name = "two-role-" + UUID.randomUUID().toString().substring(0, 8);
        LockConfiguration configuration =
                new LockConfiguration(clock.instant(), name, Duration.ofMinutes(5), Duration.ZERO);

        Optional<SimpleLock> first = provider.lock(configuration);
        Optional<SimpleLock> second = provider.lock(configuration);
        first.orElseThrow().unlock();

        assertThat(second).isEmpty();
        assertThat(provider.lock(configuration)).isPresent();
    }
}
