package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalPasswordSecurityValidator} (b2-2, B2-2/2, B2-2/3; b2-5, B2-5/P1, P2): length (12 to
 * 128), the bundled breached-password list, context terms, blocklist.
 */
class LocalPasswordSecurityValidatorTest {

    private final LocalPasswordSecurityValidator validator =
            new LocalPasswordSecurityValidator(new BreachedPasswords());

    @Test
    void aGenuinelyStrongPasswordHasNoViolations() {
        assertThat(validator.validate("xk9$mQ2vTz8!wLp4", List.of())).isEmpty();
    }

    @Test
    void shorterThanTwelveCharactersIsRejected() {
        assertThat(validator.validate("short1Pw!", List.of())).isNotEmpty();
    }

    @Test
    void exactlyTwelveCharactersIsAccepted() {
        assertThat(validator.validate("xk9mQ2vTz8wL", List.of())).isEmpty();
    }

    @Test
    void aPasswordContainingTheOrganizationNameIsRejected() {
        assertThat(validator.validate("acmecorpsecret1", List.of("Acme Corp"))).isNotEmpty();
    }

    @Test
    void aPasswordContainingTheFoundersNameIsRejected() {
        assertThat(validator.validate("janedoepassword", List.of("Jane Doe"))).isNotEmpty();
    }

    @Test
    void aPasswordContainingTheEmailLocalPartIsRejected() {
        assertThat(validator.validate("janedoe12345678", List.of("janedoe"))).isNotEmpty();
    }

    @Test
    void matchingIsCaseInsensitiveAndIgnoresWhitespace() {
        assertThat(validator.validate("ACMECORPsecret1", List.of("acme corp"))).isNotEmpty();
    }

    @Test
    void aVeryShortContextTermIsIgnoredToAvoidFalsePositives() {
        // A two-character term ("JD" initials, say) should not reject an unrelated password just
        // because "jd" happens to appear somewhere in it.
        assertThat(validator.validate("bluejaydancer1", List.of("jd"))).isEmpty();
    }

    @Test
    void aBlankOrNullContextTermIsIgnored() {
        java.util.List<String> terms = new java.util.ArrayList<>();
        terms.add("");
        terms.add(null);
        assertThat(validator.validate("xk9mQ2vTz8wL", terms)).isEmpty();
    }

    @Test
    void nullContextTermsListIsTreatedAsEmpty() {
        assertThat(validator.validate("xk9mQ2vTz8wL", null)).isEmpty();
    }

    @Test
    void wellKnownWeakPasswordsAreRejected() {
        for (String weak :
                List.of(
                        "password12345",
                        "qwertyuiop123",
                        "letmein123456",
                        "welcome1234567",
                        "iloveyou123456")) {
            assertThat(validator.validate(weak, List.of())).as(weak).isNotEmpty();
        }
    }

    // ---- b2-5: maximum length (B2-5/P2) ----

    @Test
    void exactly128CharactersIsAcceptedAnd129IsRejected() {
        String base = "xk9$mQ2vTz8!wLp4";
        String longest = base.repeat(8); // 128 characters
        assertThat(longest).hasSize(128);

        assertThat(validator.validate(longest, List.of())).isEmpty();
        assertThat(validator.validate(longest + "x", List.of()))
                .containsExactly("Password must be at most 128 characters long.");
    }

    // ---- b2-5: breached-password list (B2-5/P1) ----

    @Test
    void aPasswordOnTheBreachedListIsRejectedWhateverItsCase() {
        for (String breached : List.of("01telemike01", "01TeleMike01", "1234567890QWERTY")) {
            assertThat(validator.validate(breached, List.of()))
                    .as(breached)
                    .containsExactly(
                            "This password has appeared in a data breach. Choose a different"
                                    + " one.");
        }
    }

    @Test
    void theBreachedCheckIsAnExactMatchNotASubstringMatch() {
        // Contains a listed entry, but is not itself one.
        assertThat(validator.validate("zq-01telemike01-vp", List.of())).isEmpty();
    }

    @Test
    void aBreachedPasswordIsNeverEchoedInTheMessage() {
        assertThat(validator.validate("01telemike01", List.of()))
                .noneMatch(message -> message.toLowerCase().contains("telemike"));
    }

    @Test
    void theBundledListIsLoadedAndHoldsOnlyEntriesThePolicyCouldAccept() {
        BreachedPasswords list = new BreachedPasswords();

        assertThat(list.size()).isGreaterThan(40_000);
        assertThat(list.contains("01telemike01")).isTrue();
        assertThat(list.contains(null)).isFalse();
        // Entries shorter than the minimum were dropped when the file was built.
        assertThat(list.contains("123456")).isFalse();
    }

    @Test
    void theRawPasswordIsNeverEchoedInAViolationMessage() {
        String password = "acmecorpsecretvalue1";
        List<String> violations = validator.validate(password, List.of("Acme Corp"));

        assertThat(violations).isNotEmpty().noneMatch(message -> message.contains(password));
    }
}
