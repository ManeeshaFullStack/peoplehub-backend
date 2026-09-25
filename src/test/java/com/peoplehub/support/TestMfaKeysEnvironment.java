package com.peoplehub.support;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Supplies a throwaway MFA encryption key to every application context started from the test
 * classpath (b2-7, B2-7/7), the same way {@link TestJwtKeysEnvironment} supplies the signing key:
 * tests need no environment variables while the application itself still refuses to start without a
 * key. Generated once per JVM, never committed. Only fills in what is not already set.
 */
public class TestMfaKeysEnvironment implements EnvironmentPostProcessor {

    public static final String KEY_ID = "test-mfa-key-1";

    /** The test key, base64; random per JVM. */
    public static final String KEY = generate();

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> values = new HashMap<>();
        if (!environment.containsProperty("peoplehub.mfa.encryption-key")) {
            values.put("peoplehub.mfa.encryption-key", KEY);
        }
        if (!environment.containsProperty("peoplehub.mfa.encryption-key-id")) {
            values.put("peoplehub.mfa.encryption-key-id", KEY_ID);
        }
        if (!values.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource("testMfaKeys", values));
        }
    }

    /** A fresh random 32-byte key, base64. */
    public static String generate() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
