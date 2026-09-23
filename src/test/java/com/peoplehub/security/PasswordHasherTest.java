package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link PasswordHasher} against real Argon2id (b2-2, B2-2/1): no mocking, it's the whole point.
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void aHashedPasswordMatchesTheOriginalRawPassword() {
        String hash = hasher.hash("correct-horse-battery-staple");

        assertThat(hasher.matches("correct-horse-battery-staple", hash)).isTrue();
    }

    @Test
    void aHashedPasswordDoesNotMatchADifferentPassword() {
        String hash = hasher.hash("correct-horse-battery-staple");

        assertThat(hasher.matches("wrong-password-entirely", hash)).isFalse();
    }

    @Test
    void theStoredHashNeverContainsTheRawPassword() {
        String raw = "correct-horse-battery-staple";

        assertThat(hasher.hash(raw)).doesNotContain(raw);
    }

    @Test
    void hashingTheSamePasswordTwiceProducesDifferentHashes() {
        // Argon2 salts each hash, so two hashes of the same password never match byte-for-byte,
        // even though both verify successfully against the original password.
        String raw = "correct-horse-battery-staple";

        assertThat(hasher.hash(raw)).isNotEqualTo(hasher.hash(raw));
    }

    @Test
    void theHashIsMarkedAsArgon2() {
        assertThat(hasher.hash("correct-horse-battery-staple")).startsWith("$argon2id$");
    }
}
