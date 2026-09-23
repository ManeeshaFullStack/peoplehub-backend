package com.peoplehub.notification.inapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.PeopleHubApplication;
import com.peoplehub.support.TestOrganizations;
import com.peoplehub.support.TestcontainersConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The decision this branch exists to prove: "Redis is the cross-instance notification channel, not
 * an in-memory {@code SseEmitter} registry alone" (b1-3). Two full application instances share one
 * Postgres and one Redis; a notification written through instance A's HTTP surface must reach an
 * SSE client streaming from instance B. A plain in-JVM emitter map could never pass this test, only
 * something that fans out through Redis can.
 *
 * <p>Started programmatically, like {@code TwoRoleWiringTest}/{@code ScheduledJobInstancesTest},
 * for the same reason: two instances need one shared database and Redis, which the cached Spring
 * test context does not provide.
 */
class NotificationSseCrossInstanceTest {

    static final PostgreSQLContainer POSTGRES = TestcontainersConfiguration.newPostgresContainer();
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(TestcontainersConfiguration.REDIS_IMAGE))
                    .withExposedPorts(6379);

    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;

    @BeforeAll
    static void startEverything() {
        POSTGRES.start();
        REDIS.start();
        instanceA = startInstance();
        instanceB = startInstance();
    }

    @AfterAll
    static void stopEverything() {
        if (instanceB != null) {
            instanceB.close();
        }
        if (instanceA != null) {
            instanceA.close();
        }
        REDIS.stop();
        POSTGRES.stop();
    }

    private static ConfigurableApplicationContext startInstance() {
        return new SpringApplicationBuilder(PeopleHubApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.profiles.active=notification-test",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379));
    }

    private static int portOf(ConfigurableApplicationContext context) {
        return context.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    @Test
    void aNotificationWrittenOnOneInstanceReachesAnSseClientStreamingFromAnother()
            throws Exception {
        // b2-1 (V12): notification.organization_id now has a real FK to organization(id).
        UUID org =
                TestOrganizations.insert(
                        new JdbcTemplate(
                                new DriverManagerDataSource(
                                        POSTGRES.getJdbcUrl(),
                                        POSTGRES.getUsername(),
                                        POSTGRES.getPassword())));
        UUID employee = UUID.randomUUID();
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();

        Thread streamThread = new Thread(() -> streamFrom(instanceB, org, employee, received));
        streamThread.setDaemon(true);
        streamThread.start();

        // Give the SSE subscription a moment to register with Redis before publishing.
        Thread.sleep(1000);

        createNotification(instanceA, org, employee, "SOMETHING_HAPPENED");

        String event = received.poll(15, TimeUnit.SECONDS);
        assertThat(event)
                .as("instance B's SSE client received the event from instance A's write")
                .isNotNull();
        assertThat(event).contains("SOMETHING_HAPPENED");

        streamThread.interrupt();
    }

    private static void createNotification(
            ConfigurableApplicationContext instance, UUID org, UUID employee, String type) {
        try {
            String body =
                    "{\"organizationId\":\""
                            + org
                            + "\",\"employeeId\":\""
                            + employee
                            + "\",\"type\":\""
                            + type
                            + "\"}";
            HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://localhost:"
                                                    + portOf(instance)
                                                    + "/api/v1/notification-test/notifications"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResponse<Void> response =
                    HttpClient.newHttpClient()
                            .send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 201) {
                throw new IllegalStateException("Unexpected status: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void streamFrom(
            ConfigurableApplicationContext instance,
            UUID org,
            UUID employee,
            LinkedBlockingQueue<String> received) {
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + portOf(instance)
                                                + "/api/v1/notification-test/stream?organizationId="
                                                + org
                                                + "&employeeId="
                                                + employee))
                        .GET()
                        .build();
        try {
            HttpResponse<InputStream> response =
                    HttpClient.newHttpClient()
                            .send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while (!Thread.currentThread().isInterrupted()
                        && (line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        received.add(line.substring("data:".length()).trim());
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            // Expected when the test interrupts this thread to tear the stream down.
        }
    }
}
