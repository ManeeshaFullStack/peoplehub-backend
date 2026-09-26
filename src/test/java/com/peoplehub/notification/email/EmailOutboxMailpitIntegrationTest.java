package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.PeopleHubApplication;
import com.peoplehub.common.database.TenantContext;
import com.peoplehub.support.TestDatabaseRoles;
import com.peoplehub.support.TestOrganizations;
import com.peoplehub.support.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One thin end-to-end proof that a queued email genuinely reaches an SMTP server (b1-2 decision: a
 * scoped Mailpit {@link GenericContainer}, not GreenMail, and not relying only on {@code
 * docker/smoke.sh}). A full application instance is started against real Postgres, Redis and
 * Mailpit containers -- the exact same {@code SmtpEmailSender} code path production uses against
 * Brevo, pointed at Mailpit instead. Everything else about {@code EmailOutboxProcessor}'s behaviour
 * (retry, classification, claiming, concurrency) is proven faster and more thoroughly by {@code
 * EmailOutboxProcessorTest} against a fake sender; this test exists only to prove the real SMTP
 * wire protocol actually works.
 *
 * <p>Started programmatically, like {@code TwoRoleWiringTest}/{@code ScheduledJobInstancesTest},
 * for the same reason: the Spring test framework owns and reuses its context, and this needs
 * bespoke container wiring (Mailpit) that {@code @IntegrationTest} does not provide.
 */
class EmailOutboxMailpitIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final PostgreSQLContainer POSTGRES = TestcontainersConfiguration.newPostgresContainer();
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(TestcontainersConfiguration.REDIS_IMAGE))
                    .withExposedPorts(6379);
    // Same pinned image as docker-compose.yml's mailpit service.
    static final GenericContainer<?> MAILPIT =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "axllent/mailpit:v1.31.2@sha256:74d609a42ec279aa63c6b4622a6fa9b5408d1ad5b1d76a1c4be40a265ce0863d"))
                    .withExposedPorts(1025, 8025);

    private static ConfigurableApplicationContext app;

    @BeforeAll
    static void startEverything() {
        POSTGRES.start();
        REDIS.start();
        MAILPIT.start();
        List<String> args =
                new ArrayList<>(TestDatabaseRoles.applicationDatabaseArguments(POSTGRES));
        args.addAll(
                List.of(
                        "--server.port=0",
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--spring.mail.host=" + MAILPIT.getHost(),
                        "--spring.mail.port=" + MAILPIT.getMappedPort(1025),
                        "--peoplehub.email.from-address=test@peoplehub.example",
                        // Fast enough for a test to observe within a few seconds.
                        "--peoplehub.email.outbox.interval=1s"));
        app =
                new SpringApplicationBuilder(PeopleHubApplication.class)
                        .run(args.toArray(String[]::new));
    }

    @AfterAll
    static void stopEverything() {
        if (app != null) {
            app.close();
        }
        MAILPIT.stop();
        REDIS.stop();
        POSTGRES.stop();
    }

    @Test
    void aQueuedEmailIsActuallyDeliveredThroughMailpit() throws Exception {
        EmailOutboxWriter writer = app.getBean(EmailOutboxWriter.class);
        TransactionTemplate tx =
                new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        // Fixture setup and assertions; the application itself runs as the runtime role.
        JdbcTemplate jdbc = TestDatabaseRoles.privilegedFixtureJdbc(POSTGRES);
        // b2-1 (V12): organization_id now has a real FK to organization(id).
        UUID org = TestOrganizations.insert(jdbc);
        String recipient = "integration-test@example.com";
        EmailMessage message =
                EmailMessage.builder(org, recipient, "EMPLOYEE_INVITED")
                        .payload(
                                EmailPayload.builder()
                                        .attribute("appName", "PeopleHub")
                                        .attribute("firstName", "Integration")
                                        .attribute("inviteCode", "ZZ99YY")
                                        // b2-4: the invitation template also needs these.
                                        .attribute("organizationLoginKey", "acme-corp")
                                        .attribute("role", "EMPLOYEE")
                                        .build())
                        .build();

        // b2-8 C4 (V25): enqueued under the organization's tenant, as its request would be; the
        // worker then finds it through V24 and sends it under the same tenant.
        try (TenantContext.Scope tenant = TenantContext.open(org)) {
            tx.executeWithoutResult(status -> writer.enqueue(message));
        }

        waitUntil(
                "the outbox row to become SENT",
                20_000,
                () ->
                        "SENT"
                                .equals(
                                        jdbc.queryForObject(
                                                "SELECT status FROM email_outbox WHERE organization_id = ?",
                                                String.class,
                                                org)));

        JsonNode messages = fetchMailpitMessages().path("messages");
        assertThat(messages).hasSize(1);
        JsonNode delivered = messages.get(0);
        assertThat(delivered.path("Subject").asString())
                .isEqualTo("You're invited to PeopleHub, Integration!");
        assertThat(delivered.path("To").get(0).path("Address").asString()).isEqualTo(recipient);
    }

    private static JsonNode fetchMailpitMessages() throws Exception {
        String url =
                "http://"
                        + MAILPIT.getHost()
                        + ":"
                        + MAILPIT.getMappedPort(8025)
                        + "/api/v1/messages";
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body());
    }

    private static void waitUntil(String description, long timeoutMillis, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + description);
            }
            Thread.sleep(200);
        }
    }
}
