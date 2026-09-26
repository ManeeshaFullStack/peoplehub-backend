package com.peoplehub.notification.inapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.PeopleHubApplication;
import com.peoplehub.support.TestDatabaseRoles;
import com.peoplehub.support.TestOrganizations;
import com.peoplehub.support.TestcontainersConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The decision this branch exists to prove: "Redis is the cross-instance notification channel, not
 * an in-memory {@code SseEmitter} registry alone" (b1-3). Two full application instances share one
 * Postgres and one Redis; a notification written through instance A's HTTP surface must reach an
 * SSE client streaming from instance B. A plain in-JVM emitter map could never pass this test, only
 * something that fans out through Redis can.
 *
 * <p>b2-8 C5 adds the tenant boundary: every live-notification channel is qualified by organization
 * and employee, so two organizations, even with the same employee id, never see each other's
 * events, on any instance, before or after a reconnect.
 *
 * <p>Deterministic, whatever else runs in the JVM (b2-8 C5 carry-over: this test used to fail when
 * run alone, because a fixed one-second pause was not always enough for a cold instance to serve
 * its first SSE request and subscribe, and Redis pub/sub does not replay):
 *
 * <ul>
 *   <li>a stream is only published to after Redis itself reports the channel subscribed ({@code
 *       PUBSUB NUMSUB});
 *   <li>"nothing arrived" is proven with a fence, never a pause: after the event that must not
 *       arrive, a second event is published to the subscriber's own channel. Redis delivers to one
 *       subscriber connection in the order it processed the publishes, so anything that could have
 *       leaked would arrive before the fence.
 * </ul>
 *
 * <p>Started programmatically, like {@code TwoRoleWiringTest}/{@code ScheduledJobInstancesTest},
 * for the same reason: two instances need one shared database and Redis, which the cached Spring
 * test context does not provide.
 */
class NotificationSseCrossInstanceTest {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(15);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final PostgreSQLContainer POSTGRES = TestcontainersConfiguration.newPostgresContainer();
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(TestcontainersConfiguration.REDIS_IMAGE))
                    .withExposedPorts(6379);

    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;
    private static JdbcTemplate fixture;
    // The test's own Redis connection, only to ask Redis whether a channel is subscribed.
    private static RedisClient redisClient;
    private static StatefulRedisConnection<String, String> probe;

    @BeforeAll
    static void startEverything() {
        POSTGRES.start();
        REDIS.start();
        instanceA = startInstance();
        instanceB = startInstance();
        fixture = TestDatabaseRoles.privilegedFixtureJdbc(POSTGRES);
        redisClient =
                RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        probe = redisClient.connect();
    }

    @AfterAll
    static void stopEverything() {
        if (probe != null) {
            probe.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
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
        List<String> args =
                new ArrayList<>(TestDatabaseRoles.applicationDatabaseArguments(POSTGRES));
        args.addAll(
                List.of(
                        "--server.port=0",
                        "--spring.profiles.active=notification-test",
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379)));
        return new SpringApplicationBuilder(PeopleHubApplication.class)
                .run(args.toArray(String[]::new));
    }

    private static int portOf(ConfigurableApplicationContext context) {
        return context.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    // ---- cross-instance delivery ----

    @Test
    void aNotificationWrittenOnOneInstanceReachesAnSseClientStreamingFromAnother()
            throws Exception {
        // b2-1 (V12): notification.organization_id has a real FK to organization(id).
        UUID org = TestOrganizations.insert(fixture);
        UUID employee = UUID.randomUUID();

        try (Stream stream = Stream.open(instanceB, org, employee)) {
            createNotification(instanceA, org, employee, "SOMETHING_HAPPENED");

            JsonNode event = stream.next();
            assertThat(event.get("type").asString()).isEqualTo("SOMETHING_HAPPENED");
            // Only identifying fields travel through Redis: never the organization, the
            // employee or the payload.
            assertThat(fieldNames(event)).containsExactlyInAnyOrder("id", "type", "createdAt");
        }
    }

    // ---- tenant isolation (b2-8 C5) ----

    @Test
    void twoOrganizationsNeverSeeEachOthersEventsEvenWithTheSameEmployeeId() throws Exception {
        UUID orgA = TestOrganizations.insert(fixture);
        UUID orgB = TestOrganizations.insert(fixture);
        // The same employee id in both organizations: only the organization tells them apart.
        UUID employee = UUID.randomUUID();

        try (Stream a = Stream.open(instanceB, orgA, employee);
                Stream b = Stream.open(instanceA, orgB, employee)) {
            // Each organization's event is written through the instance the other one streams
            // from, so neither can be served locally.
            createNotification(instanceA, orgA, employee, "FOR_A");
            createNotification(instanceB, orgB, employee, "FOR_B");

            assertThat(a.next().get("type").asString()).isEqualTo("FOR_A");
            assertThat(b.next().get("type").asString()).isEqualTo("FOR_B");

            // Fences: the next event each stream sees is its own organization's.
            createNotification(instanceB, orgA, employee, "FENCE_A");
            createNotification(instanceA, orgB, employee, "FENCE_B");
            assertThat(a.next().get("type").asString()).isEqualTo("FENCE_A");
            assertThat(b.next().get("type").asString()).isEqualTo("FENCE_B");
            assertThat(a.pending()).isEmpty();
            assertThat(b.pending()).isEmpty();
        }
    }

    @Test
    void simultaneousSubscribersOfManyOrganizationsEachReceiveOnlyTheirOwn() throws Exception {
        int organizations = 4;
        UUID employee = UUID.randomUUID();
        List<UUID> orgs = new ArrayList<>();
        List<Stream> streams = new ArrayList<>();
        try {
            for (int i = 0; i < organizations; i++) {
                UUID org = TestOrganizations.insert(fixture);
                orgs.add(org);
                streams.add(Stream.open(i % 2 == 0 ? instanceA : instanceB, org, employee));
            }
            // Every organization's event, written through alternating instances.
            for (int i = 0; i < organizations; i++) {
                createNotification(
                        i % 2 == 0 ? instanceB : instanceA, orgs.get(i), employee, "EVENT_" + i);
            }
            for (int i = 0; i < organizations; i++) {
                createNotification(instanceA, orgs.get(i), employee, "FENCE_" + i);
            }

            for (int i = 0; i < organizations; i++) {
                assertThat(streams.get(i).next().get("type").asString()).isEqualTo("EVENT_" + i);
                assertThat(streams.get(i).next().get("type").asString()).isEqualTo("FENCE_" + i);
                assertThat(streams.get(i).pending()).isEmpty();
            }
        } finally {
            for (Stream stream : streams) {
                stream.close();
            }
        }
    }

    @Test
    void aReconnectAsAnotherOrganizationReceivesNothingOfTheFirst() throws Exception {
        UUID orgA = TestOrganizations.insert(fixture);
        UUID orgB = TestOrganizations.insert(fixture);
        UUID employee = UUID.randomUUID();

        try (Stream first = Stream.open(instanceB, orgA, employee)) {
            createNotification(instanceA, orgA, employee, "BEFORE");
            assertThat(first.next().get("type").asString()).isEqualTo("BEFORE");
        }

        // The same instance, the same employee id, now for the other organization.
        try (Stream second = Stream.open(instanceB, orgB, employee)) {
            createNotification(instanceA, orgA, employee, "STILL_FOR_A");
            createNotification(instanceA, orgB, employee, "FENCE_B");

            assertThat(second.next().get("type").asString()).isEqualTo("FENCE_B");
            assertThat(second.pending()).isEmpty();
        }
    }

    @Test
    void theChannelIsQualifiedByOrganizationAndEmployee() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID employee = UUID.randomUUID();

        assertThat(NotificationChannel.of(orgA, employee))
                .isEqualTo("notification:" + orgA + ":" + employee)
                .isNotEqualTo(NotificationChannel.of(orgB, employee))
                .isNotEqualTo(NotificationChannel.of(orgA, UUID.randomUUID()));
    }

    // ---- stored notifications (the test-only controller stands in for the principal) ----

    @Test
    void anotherOrganizationsNotificationCanBeNeitherListedNorMarkedRead() throws Exception {
        UUID orgA = TestOrganizations.insert(fixture);
        UUID orgB = TestOrganizations.insert(fixture);
        UUID employee = UUID.randomUUID();
        createNotification(instanceA, orgB, employee, "B_ONLY");
        long bNotification =
                fixture.queryForObject(
                        "SELECT id FROM notification WHERE organization_id = ?", Long.class, orgB);

        // Organization A asks for B's employee's notifications, and marks B's notification read.
        JsonNode listed =
                get(instanceB, "/notifications?organizationId=" + orgA + "&employeeId=" + employee);
        assertThat(listed.get("totalElements").asLong()).isZero();
        assertThat(listed.get("items").size()).isZero();
        int status =
                send(
                        instanceB,
                        "PATCH",
                        "/notifications/"
                                + bNotification
                                + "/read?organizationId="
                                + orgA
                                + "&employeeId="
                                + employee);
        assertThat(status).isEqualTo(200);

        assertThat(
                        fixture.queryForObject(
                                "SELECT read FROM notification WHERE id = ?",
                                Boolean.class,
                                bNotification))
                .as("B's notification is untouched")
                .isFalse();
        // B itself still sees it.
        JsonNode own =
                get(instanceA, "/notifications?organizationId=" + orgB + "&employeeId=" + employee);
        assertThat(own.get("totalElements").asLong()).isEqualTo(1);
    }

    // ---- helpers ----

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static void createNotification(
            ConfigurableApplicationContext instance, UUID org, UUID employee, String type) {
        String body =
                "{\"organizationId\":\""
                        + org
                        + "\",\"employeeId\":\""
                        + employee
                        + "\",\"type\":\""
                        + type
                        + "\"}";
        try {
            HttpResponse<Void> response =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(uri(instance, "/notifications"))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(body))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding());
            // 201 only after the transaction committed and its event was published (the publisher
            // is an AFTER_COMMIT listener on the request thread), so publish order is call order.
            assertThat(response.statusCode()).isEqualTo(201);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode get(ConfigurableApplicationContext instance, String path)
            throws Exception {
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(uri(instance, path)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static int send(ConfigurableApplicationContext instance, String method, String path)
            throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(uri(instance, path))
                                .method(method, HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static URI uri(ConfigurableApplicationContext instance, String path) {
        return URI.create(
                "http://localhost:" + portOf(instance) + "/api/v1/notification-test" + path);
    }

    /** How many subscriber connections Redis has on {@code channel}, as Redis itself reports. */
    private static long subscribers(String channel) {
        Long count = probe.sync().pubsubNumsub(channel).get(channel);
        return count == null ? 0 : count;
    }

    /** An SSE client of one instance, for one organization's employee. */
    private static final class Stream implements AutoCloseable {

        private final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        private final Thread reader;
        private volatile InputStream body;

        private Stream(ConfigurableApplicationContext instance, UUID org, UUID employee) {
            URI uri = uri(instance, "/stream?organizationId=" + org + "&employeeId=" + employee);
            reader = new Thread(() -> read(uri), "sse-" + org);
            reader.setDaemon(true);
        }

        /** Opens the stream and returns once Redis reports its channel subscribed. */
        static Stream open(ConfigurableApplicationContext instance, UUID org, UUID employee)
                throws InterruptedException {
            String channel = NotificationChannel.of(org, employee);
            long before = subscribers(channel);
            Stream stream = new Stream(instance, org, employee);
            stream.reader.start();
            long deadline = System.nanoTime() + EVENT_TIMEOUT.toNanos();
            while (subscribers(channel) <= before) {
                if (System.nanoTime() > deadline) {
                    stream.close();
                    throw new AssertionError("the stream never subscribed to " + channel);
                }
                Thread.sleep(20); // a readiness poll of Redis, not a guess at timing
            }
            return stream;
        }

        /** The next event, waiting at most {@link #EVENT_TIMEOUT}. */
        JsonNode next() throws InterruptedException {
            String data = events.poll(EVENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(data).as("an event arrived").isNotNull();
            return JSON.readTree(data);
        }

        List<String> pending() {
            return new ArrayList<>(events);
        }

        private void read(URI uri) {
            try {
                HttpResponse<InputStream> response =
                        HttpClient.newHttpClient()
                                .send(
                                        HttpRequest.newBuilder(uri).GET().build(),
                                        HttpResponse.BodyHandlers.ofInputStream());
                body = response.body();
                try (BufferedReader lines =
                        new BufferedReader(
                                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            events.add(line.substring("data:".length()).trim());
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                // The test closed the stream.
            }
        }

        @Override
        public void close() {
            reader.interrupt();
            InputStream open = body;
            if (open != null) {
                try {
                    open.close();
                } catch (IOException ignored) {
                    // Already closed.
                }
            }
        }
    }
}
