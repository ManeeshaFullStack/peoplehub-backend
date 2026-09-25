package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.security.SecureTokens;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Recovery codes (b2-7, B2-7/8): ten unique 80-bit codes in four groups of four, stored only as
 * SHA-256 hashes of their normalized form, matched however they are typed.
 */
class RecoveryCodesTest {

    private final SecureTokens secureTokens = new SecureTokens();
    private final RecoveryCodes recoveryCodes = new RecoveryCodes(secureTokens);

    @Test
    void tenUniqueCodesInFourGroupsOfFour() {
        List<String> codes = recoveryCodes.generate();

        assertThat(codes).hasSize(10).doesNotHaveDuplicates();
        assertThat(codes).allMatch(code -> code.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}"));
    }

    @Test
    void eachCodeCarries80RandomBits() {
        for (String code : recoveryCodes.generate()) {
            assertThat(Base32.decode(code.replace("-", "")))
                    .hasValueSatisfying(b -> assertThat(b).hasSize(10));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.addAll(recoveryCodes.generate());
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    void theStoredHashIsTheSha256OfTheNormalizedCode() {
        String code = recoveryCodes.generate().getFirst();
        String normalized = code.replace("-", "");

        assertThat(recoveryCodes.hash(code)).contains(secureTokens.hash(normalized));
        assertThat(recoveryCodes.hash(code).orElseThrow())
                .hasSize(64)
                .doesNotContain(normalized)
                .doesNotContain(code);
    }

    @Test
    void aCodeMatchesHoweverItIsTyped() {
        String code = recoveryCodes.generate().getFirst();
        String expected = recoveryCodes.hash(code).orElseThrow();
        String bare = code.replace("-", "");

        for (String typed :
                new String[] {
                    bare,
                    bare.toLowerCase(),
                    code.toLowerCase(),
                    " " + code + " ",
                    code.replace("-", " "),
                    bare.substring(0, 8) + "--" + bare.substring(8)
                }) {
            assertThat(recoveryCodes.hash(typed)).as(typed).contains(expected);
        }
    }

    @Test
    void anythingThatCannotBeACodeIsRejectedWithoutHashing() {
        String code = recoveryCodes.generate().getFirst().replace("-", "");

        for (String invalid :
                new String[] {
                    null,
                    "",
                    code.substring(1),
                    code + "A",
                    code.substring(0, 15) + "1",
                    code.substring(0, 15) + "0",
                    code.substring(0, 15) + "_",
                    "123456"
                }) {
            assertThat(recoveryCodes.hash(invalid)).as(String.valueOf(invalid)).isEmpty();
        }
    }

    @Test
    void differentCodesHaveDifferentHashes() {
        List<String> codes = recoveryCodes.generate();

        assertThat(codes.stream().map(c -> recoveryCodes.hash(c).orElseThrow()).distinct())
                .hasSize(10);
    }
}
