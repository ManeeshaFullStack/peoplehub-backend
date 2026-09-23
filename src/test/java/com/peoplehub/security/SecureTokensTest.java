package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** {@link SecureTokens} (b2-2, B2-2/5): raw token generation and hashing. */
class SecureTokensTest {

    // AuditDetails'/EmailPayload's shared token pattern (B0-6/11): the raw token must fit it, so
    // it can be carried in an audit attribute or an email payload attribute if ever needed.
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final SecureTokens tokens = new SecureTokens();

    @Test
    void generateRawProducesA64CharacterHexToken() {
        String raw = tokens.generateRaw();

        assertThat(raw).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void generateRawFitsTheAuditAndEmailPayloadTokenPattern() {
        assertThat(TOKEN_PATTERN.matcher(tokens.generateRaw()).matches()).isTrue();
    }

    @Test
    void twoGeneratedTokensAreDifferent() {
        assertThat(tokens.generateRaw()).isNotEqualTo(tokens.generateRaw());
    }

    @Test
    void hashOfTheSameRawTokenIsAlwaysTheSame() {
        String raw = tokens.generateRaw();

        assertThat(tokens.hash(raw)).isEqualTo(tokens.hash(raw));
    }

    @Test
    void hashOfDifferentRawTokensDiffer() {
        assertThat(tokens.hash(tokens.generateRaw()))
                .isNotEqualTo(tokens.hash(tokens.generateRaw()));
    }

    @Test
    void hashNeverEqualsTheRawTokenItself() {
        String raw = tokens.generateRaw();

        assertThat(tokens.hash(raw)).isNotEqualTo(raw);
    }

    @Test
    void hashIsA64CharacterHexSha256Digest() {
        assertThat(tokens.hash("some-raw-token")).hasSize(64).matches("[0-9a-f]{64}");
    }
}
