package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * RFC 6238 TOTP (b2-7, B2-7/5): the RFC's own SHA-1 test vectors, the six-digit form, drift of one
 * step either way and no further, replay protection, malformed input, and the otpauth URI.
 */
class TotpTest {

    /** RFC 6238 Appendix B: the SHA-1 seed. */
    private static final byte[] RFC_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** RFC 6238 Appendix B, SHA-1 column: Unix time to eight-digit TOTP. */
    private static final Map<Long, String> RFC_VECTORS =
            Map.of(
                    59L, "94287082",
                    1111111109L, "07081804",
                    1111111111L, "14050471",
                    1234567890L, "89005924",
                    2000000000L, "69279037",
                    20000000000L, "65353130");

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:15Z");

    private static String codeAt(byte[] secret, Instant instant) {
        return Totp.code(secret, Totp.step(instant), Totp.DIGITS);
    }

    @Test
    void theRfc6238Sha1VectorsMatch() {
        RFC_VECTORS.forEach(
                (seconds, expected) ->
                        assertThat(
                                        Totp.code(
                                                RFC_SECRET,
                                                Totp.step(Instant.ofEpochSecond(seconds)),
                                                8))
                                .as("t=" + seconds)
                                .isEqualTo(expected));
    }

    @Test
    void theSixDigitCodeIsTheLastSixDigitsOfTheRfcValue() {
        RFC_VECTORS.forEach(
                (seconds, expected) ->
                        assertThat(codeAt(RFC_SECRET, Instant.ofEpochSecond(seconds)))
                                .as("t=" + seconds)
                                .isEqualTo(expected.substring(2)));
    }

    @Test
    void theCurrentCodeVerifiesAndReturnsItsStep() {
        byte[] secret = Totp.newSecret();

        assertThat(Totp.verify(secret, codeAt(secret, NOW), NOW, null))
                .isEqualTo(OptionalLong.of(Totp.step(NOW)));
    }

    @Test
    void oneStepOfDriftEitherWayIsAcceptedButTwoIsNot() {
        byte[] secret = Totp.newSecret();
        long now = Totp.step(NOW);

        assertThat(Totp.verify(secret, Totp.code(secret, now - 1, 6), NOW, null))
                .isEqualTo(OptionalLong.of(now - 1));
        assertThat(Totp.verify(secret, Totp.code(secret, now + 1, 6), NOW, null))
                .isEqualTo(OptionalLong.of(now + 1));
        assertThat(Totp.verify(secret, Totp.code(secret, now - 2, 6), NOW, null)).isEmpty();
        assertThat(Totp.verify(secret, Totp.code(secret, now + 2, 6), NOW, null)).isEmpty();
    }

    @Test
    void aCodeIsNeverAcceptedTwiceNorAnOlderOne() {
        byte[] secret = Totp.newSecret();
        long now = Totp.step(NOW);
        String code = Totp.code(secret, now, 6);

        long accepted = Totp.verify(secret, code, NOW, null).orElseThrow();

        assertThat(Totp.verify(secret, code, NOW, accepted)).as("the same code again").isEmpty();
        assertThat(Totp.verify(secret, Totp.code(secret, now - 1, 6), NOW, accepted))
                .as("the previous step's code")
                .isEmpty();
        assertThat(Totp.verify(secret, Totp.code(secret, now + 1, 6), NOW, accepted))
                .as("the next step's code is still fine")
                .isEqualTo(OptionalLong.of(now + 1));
    }

    @Test
    void theNextStepIsAcceptedHalfAMinuteLater() {
        byte[] secret = Totp.newSecret();
        long accepted = Totp.verify(secret, codeAt(secret, NOW), NOW, null).orElseThrow();
        Instant later = NOW.plusSeconds(30);

        assertThat(Totp.verify(secret, codeAt(secret, later), later, accepted))
                .isEqualTo(OptionalLong.of(accepted + 1));
    }

    @Test
    void anotherSecretsCodeIsRejected() {
        byte[] secret = Totp.newSecret();
        byte[] other = Totp.newSecret();

        assertThat(Totp.verify(secret, codeAt(other, NOW), NOW, null)).isEmpty();
    }

    @Test
    void spacesInsideACodeAreIgnored() {
        byte[] secret = Totp.newSecret();
        String code = codeAt(secret, NOW);

        assertThat(Totp.verify(secret, code.substring(0, 3) + " " + code.substring(3), NOW, null))
                .isPresent();
        assertThat(Totp.verify(secret, " " + code + " ", NOW, null)).isPresent();
    }

    @Test
    void anythingThatIsNotExactlySixDigitsNeverMatches() {
        byte[] secret = Totp.newSecret();
        String code = codeAt(secret, NOW);

        for (String malformed :
                new String[] {
                    null,
                    "",
                    code.substring(1),
                    code + "0",
                    "12a456",
                    "１２３４５６",
                    "-12345",
                    Totp.code(secret, Totp.step(NOW), 8)
                }) {
            assertThat(Totp.verify(secret, malformed, NOW, null))
                    .as(String.valueOf(malformed))
                    .isEmpty();
        }
    }

    @Test
    void newSecretsAre160RandomBits() {
        byte[] first = Totp.newSecret();
        byte[] second = Totp.newSecret();

        assertThat(first).hasSize(20);
        assertThat(second).hasSize(20).isNotEqualTo(first);
        assertThat(Totp.displaySecret(first)).hasSize(32).matches("[A-Z2-7]{32}");
    }

    @Test
    void theOtpauthUriCarriesTheStandardParametersAndEncodesTheLabel() {
        byte[] secret = RFC_SECRET;

        String uri = Totp.otpauthUri("PeopleHub", "jane.doe+hr@example.com", secret);

        assertThat(uri)
                .isEqualTo(
                        "otpauth://totp/PeopleHub:jane.doe%2Bhr%40example.com"
                                + "?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                                + "&issuer=PeopleHub&algorithm=SHA1&digits=6&period=30");
        assertThat(URI.create(uri).getScheme()).isEqualTo("otpauth");
        assertThat(Totp.otpauthUri("People Hub", "Jane Doe", secret))
                .startsWith("otpauth://totp/People%20Hub:Jane%20Doe?")
                .contains("&issuer=People%20Hub&");
    }
}
