package com.peoplehub.support;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Supplies the test signing key to every application context started from the test classpath
 * (integration tests, web slices and {@code ./mvnw spring-boot:test-run}), so they keep needing no
 * environment variables while the application itself still refuses to start without a key (b2-3,
 * B2-3/2). Registered in {@code src/test/resources/META-INF/spring.factories}; only fills in what
 * is not already set, so a test can still configure its own key.
 */
public class TestJwtKeysEnvironment implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> values = new HashMap<>();
        if (!environment.containsProperty("peoplehub.jwt.signing-key")) {
            values.put("peoplehub.jwt.signing-key", TestJwtKeys.privatePem(TestJwtKeys.SIGNING));
        }
        if (!environment.containsProperty("peoplehub.jwt.signing-key-id")) {
            values.put("peoplehub.jwt.signing-key-id", TestJwtKeys.KEY_ID);
        }
        if (!values.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource("testJwtKeys", values));
        }
    }
}
