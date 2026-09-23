package com.peoplehub.security;

import java.util.Objects;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Password hashing only (Spec 8.2, 15; b2-2, B2-2/1) -- Argon2id via {@link Argon2PasswordEncoder}
 * used standalone, not the full {@code spring-boot-starter-security}. There is no {@code
 * SecurityFilterChain}, no {@code AuthenticationManager} and no login endpoint anywhere in this
 * codebase: those are {@code b2-3}'s job. This class only turns a raw password into a hash to
 * store, and later checks a raw password against a stored hash; it never decides whether a request
 * is authenticated.
 *
 * <p>{@link Argon2PasswordEncoder#defaultsForSpringSecurity_v5_8()} rather than hand-tuned
 * parameters (B2-2/1): Spring Security's own maintained defaults, not a locally chosen memory/time
 * cost that would need its own justification and periodic review.
 */
@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    /** Hashes a raw password for storage. The raw value is never logged or echoed. */
    public String hash(String rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword");
        return encoder.encode(rawPassword);
    }

    /** Whether a raw password matches a previously stored hash. */
    public boolean matches(String rawPassword, String hash) {
        Objects.requireNonNull(rawPassword, "rawPassword");
        Objects.requireNonNull(hash, "hash");
        return encoder.matches(rawPassword, hash);
    }
}
