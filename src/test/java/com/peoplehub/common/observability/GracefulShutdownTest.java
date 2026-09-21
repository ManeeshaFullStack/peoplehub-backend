package com.peoplehub.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.PeopleHubApplication;
import com.peoplehub.support.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Graceful shutdown (Spec 16.4): a request that is already being served finishes before the
 * application stops, so a deploy or restart does not cut off an attendance action mid-flight. This
 * starts a real server, closes the application while a slow request is in flight and checks the
 * client still gets its answer.
 *
 * <p>It starts its own application rather than using the Spring test context, because the test
 * framework owns (and re-uses) that one and must not have it closed underneath it.
 */
class GracefulShutdownTest {

    @Test
    void gracefulShutdownIsConfiguredAndLetsARequestInFlightFinish() throws Exception {
        ConfigurableApplicationContext context =
                SpringApplication.from(PeopleHubApplication::main)
                        .with(TestcontainersConfiguration.class)
                        .run("--server.port=0", "--spring.profiles.active=api-test")
                        .getApplicationContext();
        try {
            assertThat(context.getEnvironment().getProperty("server.shutdown"))
                    .isEqualTo("graceful");
            assertThat(
                            context.getEnvironment()
                                    .getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                    .isEqualTo("30s");

            String port = context.getEnvironment().getProperty("local.server.port");
            HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://localhost:"
                                                    + port
                                                    + "/api/v1/api-test/slow?millis=2500"))
                            .timeout(Duration.ofSeconds(30))
                            .build();

            CompletableFuture<HttpResponse<String>> inFlight =
                    HttpClient.newHttpClient()
                            .sendAsync(request, HttpResponse.BodyHandlers.ofString());
            Thread.sleep(700); // long enough for the server to be inside the handler

            Thread shutdown = new Thread(context::close, "test-shutdown");
            shutdown.start();

            HttpResponse<String> response = inFlight.get(30, TimeUnit.SECONDS);
            shutdown.join(TimeUnit.SECONDS.toMillis(30));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("finished");
            assertThat(context.isActive()).isFalse();
        } finally {
            context.close();
        }
    }
}
