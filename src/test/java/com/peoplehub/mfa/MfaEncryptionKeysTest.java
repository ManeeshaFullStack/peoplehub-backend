package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Loading the MFA encryption keys (b2-7, B2-7/7): valid configurations load; every invalid one
 * stops startup with a message that names the setting but never contains key material.
 */
class MfaEncryptionKeysTest {

    private static String randomKey(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static final String KEY = randomKey(32);

    @Test
    void aValidKeyLoads() {
        MfaEncryptionKeys keys = MfaEncryptionKeys.from(KEY, "mfa-2026-09", "");

        assertThat(keys.currentKeyId()).isEqualTo("mfa-2026-09");
        assertThat(keys.currentKey().getAlgorithm()).isEqualTo("AES");
        assertThat(keys.currentKey().getEncoded()).isEqualTo(Base64.getDecoder().decode(KEY));
        assertThat(keys.key("mfa-2026-09")).contains(keys.currentKey());
        assertThat(keys.key("unknown")).isEmpty();
    }

    @Test
    void whitespaceInsideTheBase64IsIgnoredAndTheIdIsTrimmed() {
        String spaced = KEY.substring(0, 10) + "\n " + KEY.substring(10);

        MfaEncryptionKeys keys = MfaEncryptionKeys.from(spaced, "  k1 ", null);

        assertThat(keys.currentKeyId()).isEqualTo("k1");
    }

    @Test
    void previousKeysDecryptOnlyAndAreLookedUpById() {
        String previous = randomKey(32);
        String older = randomKey(32);

        MfaEncryptionKeys keys =
                MfaEncryptionKeys.from(KEY, "k3", "k2:" + previous + ", k1:" + older + ",");

        assertThat(keys.currentKeyId()).isEqualTo("k3");
        assertThat(keys.key("k2").orElseThrow().getEncoded())
                .isEqualTo(Base64.getDecoder().decode(previous));
        assertThat(keys.key("k1").orElseThrow().getEncoded())
                .isEqualTo(Base64.getDecoder().decode(older));
    }

    @Test
    void aMissingKeyOrKeyIdStopsStartup() {
        assertThatThrownBy(() -> MfaEncryptionKeys.from(null, "k", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PEOPLEHUB_MFA_ENCRYPTION_KEY is required");
        assertThatThrownBy(() -> MfaEncryptionKeys.from("  ", "k", null))
                .hasMessage("PEOPLEHUB_MFA_ENCRYPTION_KEY is required");
        assertThatThrownBy(() -> MfaEncryptionKeys.from(KEY, null, null))
                .hasMessageStartingWith("PEOPLEHUB_MFA_ENCRYPTION_KEY_ID needs a key id");
        assertThatThrownBy(() -> MfaEncryptionKeys.from(KEY, "", null))
                .hasMessageStartingWith("PEOPLEHUB_MFA_ENCRYPTION_KEY_ID needs a key id");
    }

    @Test
    void aKeyIdWithTheStorageSeparatorOrOtherCharactersIsRejected() {
        for (String kid : new String[] {"k:1", "k 1", "k/1", "x".repeat(65)}) {
            assertThatThrownBy(() -> MfaEncryptionKeys.from(KEY, kid, null))
                    .as(kid)
                    .hasMessageStartingWith("PEOPLEHUB_MFA_ENCRYPTION_KEY_ID needs a key id");
        }
    }

    @Test
    void aKeyThatIsNotBase64OrNot32BytesIsRejected() {
        assertThatThrownBy(() -> MfaEncryptionKeys.from("not*base64!", "k", null))
                .hasMessage("PEOPLEHUB_MFA_ENCRYPTION_KEY must be base64");
        for (int bytes : new int[] {16, 24, 31, 33, 64}) {
            String key = randomKey(bytes);
            assertThatThrownBy(() -> MfaEncryptionKeys.from(key, "k", null))
                    .as(bytes + " bytes")
                    .hasMessage(
                            "PEOPLEHUB_MFA_ENCRYPTION_KEY must decode to exactly 32 bytes (AES-256)");
        }
    }

    @Test
    void aConstantKeyIsRejected() {
        String zeros = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] sevens = new byte[32];
        java.util.Arrays.fill(sevens, (byte) 7);

        assertThatThrownBy(() -> MfaEncryptionKeys.from(zeros, "k", null))
                .hasMessageStartingWith("PEOPLEHUB_MFA_ENCRYPTION_KEY must be random");
        assertThatThrownBy(
                        () ->
                                MfaEncryptionKeys.from(
                                        Base64.getEncoder().encodeToString(sevens), "k", null))
                .hasMessageStartingWith("PEOPLEHUB_MFA_ENCRYPTION_KEY must be random");
    }

    @Test
    void badPreviousKeysAreRejected() {
        String previous = randomKey(32);

        assertThatThrownBy(() -> MfaEncryptionKeys.from(KEY, "k2", previous))
                .hasMessage("PEOPLEHUB_MFA_PREVIOUS_ENCRYPTION_KEYS entries must be kid:base64");
        assertThatThrownBy(() -> MfaEncryptionKeys.from(KEY, "k2", "k2:" + previous))
                .hasMessage(
                        "PEOPLEHUB_MFA_PREVIOUS_ENCRYPTION_KEYS repeats a key id already in use");
        assertThatThrownBy(
                        () ->
                                MfaEncryptionKeys.from(
                                        KEY, "k3", "k1:" + previous + ",k1:" + randomKey(32)))
                .hasMessage(
                        "PEOPLEHUB_MFA_PREVIOUS_ENCRYPTION_KEYS repeats a key id already in use");
        assertThatThrownBy(() -> MfaEncryptionKeys.from(KEY, "k2", "k1:" + randomKey(16)))
                .hasMessage(
                        "PEOPLEHUB_MFA_PREVIOUS_ENCRYPTION_KEYS must decode to exactly 32 bytes"
                                + " (AES-256)");
        assertThatThrownBy(() -> MfaEncryptionKeys.from(KEY, "k2", "bad id:" + previous))
                .hasMessageStartingWith("PEOPLEHUB_MFA_PREVIOUS_ENCRYPTION_KEYS needs a key id");
    }

    @Test
    void noErrorMessageEverContainsKeyMaterial() {
        String wrongLength = randomKey(16);
        String previous = randomKey(32);
        String[][] configurations = {
            {wrongLength, "k", null},
            {"not*base64!" + KEY, "k", null},
            {KEY, "k2", "k1:" + wrongLength},
            {KEY, "k2", previous},
            {KEY, "k2", "k2:" + previous}
        };

        for (String[] c : configurations) {
            assertThatThrownBy(() -> MfaEncryptionKeys.from(c[0], c[1], c[2]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasNoCause()
                    .satisfies(
                            e ->
                                    assertThat(e.getMessage())
                                            .doesNotContain(KEY)
                                            .doesNotContain(wrongLength)
                                            .doesNotContain(previous));
        }
    }

    @Test
    void toStringNamesTheCurrentKeyIdOnly() {
        MfaEncryptionKeys keys = MfaEncryptionKeys.from(KEY, "k2", "k1:" + randomKey(32));

        assertThat(keys.toString())
                .isEqualTo("MfaEncryptionKeys[currentKeyId=k2, keys=2]")
                .doesNotContain(KEY);
    }
}
