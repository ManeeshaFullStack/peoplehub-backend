package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalPasswordSecurityValidator} (b2-2, B2-2/2, B2-2/3): length, context terms, blocklist.
 */
class LocalPasswordSecurityValidatorTest {

    private final LocalPasswordSecurityValidator validator = new LocalPasswordSecurityValidator();

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

    @Test
    void theRawPasswordIsNeverEchoedInAViolationMessage() {
        String password = "acmecorpsecretvalue1";
        List<String> violations = validator.validate(password, List.of("Acme Corp"));

        assertThat(violations).isNotEmpty().noneMatch(message -> message.contains(password));
    }
}
