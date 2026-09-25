package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AES-256-GCM encryption of TOTP secrets (b2-7, B2-7/7): round trip, storage format, a fresh nonce
 * every time, binding to the account, tamper detection, key rotation, and failures that reveal
 * nothing.
 */
class MfaSecretCipherTest {

    private static final String KEY = randomKey();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID EMPLOYEE = UUID.randomUUID();

    private final MfaSecretCipher cipher =
            new MfaSecretCipher(MfaEncryptionKeys.from(KEY, "k1", ""));

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void aSecretRoundTripsForItsOwnAccount() {
        byte[] secret = Totp.newSecret();

        String stored = cipher.encrypt(secret, ORG, EMPLOYEE);

        assertThat(cipher.decrypt(stored, ORG, EMPLOYEE)).isEqualTo(secret);
    }

    @Test
    void theStoredFormIsKeyIdColonBase64OfNonceCiphertextAndTag() {
        byte[] secret = Totp.newSecret();

        String stored = cipher.encrypt(secret, ORG, EMPLOYEE);

        assertThat(stored).startsWith("k1:").hasSizeLessThanOrEqualTo(512);
        byte[] body = Base64.getDecoder().decode(stored.substring(3));
        // 12-byte nonce + 20-byte secret + 16-byte tag.
        assertThat(body).hasSize(12 + Totp.SECRET_BYTES + 16);
        assertThat(stored)
                .doesNotContain(HexFormat.of().formatHex(secret))
                .doesNotContain(Totp.displaySecret(secret))
                .doesNotContain(Base64.getEncoder().encodeToString(secret));
    }

    @Test
    void everyEncryptionUsesAFreshNonce() {
        byte[] secret = Totp.newSecret();

        String first = cipher.encrypt(secret, ORG, EMPLOYEE);
        String second = cipher.encrypt(secret, ORG, EMPLOYEE);

        assertThat(first).isNotEqualTo(second);
        byte[] firstNonce =
                java.util.Arrays.copyOf(Base64.getDecoder().decode(first.substring(3)), 12);
        byte[] secondNonce =
                java.util.Arrays.copyOf(Base64.getDecoder().decode(second.substring(3)), 12);
        assertThat(firstNonce).isNotEqualTo(secondNonce);
    }

    @Test
    void aSecretCopiedToAnotherAccountCannotBeDecrypted() {
        String stored = cipher.encrypt(Totp.newSecret(), ORG, EMPLOYEE);

        assertThatThrownBy(() -> cipher.decrypt(stored, ORG, UUID.randomUUID()))
                .as("another employee of the same organization")
                .isInstanceOf(MfaSecretUnreadableException.class);
        assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID(), EMPLOYEE))
                .as("the same employee id under another organization")
                .isInstanceOf(MfaSecretUnreadableException.class);
        assertThatThrownBy(() -> cipher.decrypt(stored, EMPLOYEE, ORG))
                .as("ids swapped")
                .isInstanceOf(MfaSecretUnreadableException.class);
    }

    @Test
    void anyTamperingIsDetected() {
        String stored = cipher.encrypt(Totp.newSecret(), ORG, EMPLOYEE);
        byte[] body = Base64.getDecoder().decode(stored.substring(3));

        for (int index : new int[] {0, 11, 12, 20, body.length - 1}) {
            byte[] tampered = body.clone();
            tampered[index] ^= 0x01;
            String value = "k1:" + Base64.getEncoder().encodeToString(tampered);
            assertThatThrownBy(() -> cipher.decrypt(value, ORG, EMPLOYEE))
                    .as("byte " + index)
                    .isInstanceOf(MfaSecretUnreadableException.class);
        }
    }

    @Test
    void malformedOrUnknownValuesAreUnreadable() {
        String stored = cipher.encrypt(Totp.newSecret(), ORG, EMPLOYEE);
        String body = stored.substring(3);

        for (String value :
                new String[] {
                    null,
                    "",
                    "k1",
                    ":" + body,
                    "k9:" + body,
                    "k1:not base64!",
                    "k1:" + Base64.getEncoder().encodeToString(new byte[28]),
                    body
                }) {
            assertThatThrownBy(() -> cipher.decrypt(value, ORG, EMPLOYEE))
                    .as(String.valueOf(value))
                    .isInstanceOf(MfaSecretUnreadableException.class);
        }
    }

    @Test
    void afterARotationOldSecretsStayReadableAndNewOnesUseTheNewKey() {
        byte[] oldSecret = Totp.newSecret();
        String storedWithOldKey = cipher.encrypt(oldSecret, ORG, EMPLOYEE);

        MfaSecretCipher rotated =
                new MfaSecretCipher(MfaEncryptionKeys.from(randomKey(), "k2", "k1:" + KEY));

        assertThat(rotated.decrypt(storedWithOldKey, ORG, EMPLOYEE)).isEqualTo(oldSecret);
        byte[] newSecret = Totp.newSecret();
        String storedWithNewKey = rotated.encrypt(newSecret, ORG, EMPLOYEE);
        assertThat(storedWithNewKey).startsWith("k2:");
        assertThat(rotated.decrypt(storedWithNewKey, ORG, EMPLOYEE)).isEqualTo(newSecret);
        // The instance that only knows the old key cannot read the new value.
        assertThatThrownBy(() -> cipher.decrypt(storedWithNewKey, ORG, EMPLOYEE))
                .isInstanceOf(MfaSecretUnreadableException.class);
    }

    @Test
    void removingAKeyThatIsStillInUseMakesItsSecretsUnreadable() {
        String storedWithOldKey = cipher.encrypt(Totp.newSecret(), ORG, EMPLOYEE);

        MfaSecretCipher withoutOldKey =
                new MfaSecretCipher(MfaEncryptionKeys.from(randomKey(), "k2", ""));

        assertThatThrownBy(() -> withoutOldKey.decrypt(storedWithOldKey, ORG, EMPLOYEE))
                .isInstanceOf(MfaSecretUnreadableException.class);
    }

    @Test
    void aSameIdWithADifferentKeyCannotDecrypt() {
        String stored = cipher.encrypt(Totp.newSecret(), ORG, EMPLOYEE);

        MfaSecretCipher sameIdOtherKey =
                new MfaSecretCipher(MfaEncryptionKeys.from(randomKey(), "k1", ""));

        assertThatThrownBy(() -> sameIdOtherKey.decrypt(stored, ORG, EMPLOYEE))
                .isInstanceOf(MfaSecretUnreadableException.class);
    }

    @Test
    void theFailureRevealsNothing() {
        byte[] secret = Totp.newSecret();
        String stored = cipher.encrypt(secret, ORG, EMPLOYEE);

        assertThatThrownBy(() -> cipher.decrypt(stored, ORG, UUID.randomUUID()))
                .isInstanceOf(MfaSecretUnreadableException.class)
                .hasMessage("The stored MFA secret could not be decrypted")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getStackTrace()).isEmpty())
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty())
                .satisfies(
                        e ->
                                assertThat(e.toString())
                                        .doesNotContain(stored)
                                        .doesNotContain(KEY)
                                        .doesNotContain(Totp.displaySecret(secret)));
    }
}
