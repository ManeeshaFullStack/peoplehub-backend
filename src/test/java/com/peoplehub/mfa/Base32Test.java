package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** RFC 4648 base32 without padding (b2-7): the RFC's own test vectors, round trips, rejects. */
class Base32Test {

    /** RFC 4648 section 10, padding removed. */
    private static final Map<String, String> VECTORS =
            Map.of(
                    "", "",
                    "f", "MY",
                    "fo", "MZXQ",
                    "foo", "MZXW6",
                    "foob", "MZXW6YQ",
                    "fooba", "MZXW6YTB",
                    "foobar", "MZXW6YTBOI");

    @Test
    void theRfcVectorsEncodeAndDecode() {
        VECTORS.forEach(
                (plain, encoded) -> {
                    byte[] bytes = plain.getBytes(StandardCharsets.US_ASCII);
                    assertThat(Base32.encode(bytes)).as(plain).isEqualTo(encoded);
                    assertThat(Base32.decode(encoded)).as(encoded).hasValue(bytes);
                });
    }

    @Test
    void randomBytesRoundTripAndDecodingIgnoresCase() {
        SecureRandom random = new SecureRandom();
        for (int length = 1; length <= 40; length++) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            String encoded = Base32.encode(bytes);
            assertThat(Base32.decode(encoded)).hasValue(bytes);
            assertThat(Base32.decode(encoded.toLowerCase())).hasValue(bytes);
        }
    }

    @Test
    void textOutsideTheAlphabetOrOfAnImpossibleLengthIsRejected() {
        for (String invalid :
                new String[] {"MZXW1", "MZXW8", "MZ=W6", "MZXW 6", "M", "MZX", "MZXW6Y"}) {
            assertThat(Base32.decode(invalid)).as(invalid).isEmpty();
        }
    }
}
