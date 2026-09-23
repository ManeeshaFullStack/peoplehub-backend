package com.peoplehub.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** {@link OrganizationLoginKeys#normalize} (b2-2). */
class OrganizationLoginKeysTest {

    // organization's own shape: lower(btrim(...)) (ck_organization_login_key_is_normalized, V8)
    // plus this class's own guarantee that only [a-z0-9-] ever appears, with no leading/trailing
    // dash.
    private static final Pattern SHAPE = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    @Test
    void lowerCasesAndTrims() {
        assertThat(OrganizationLoginKeys.normalize("  Acme Corp  ")).isEqualTo("acme-corp");
    }

    @Test
    void collapsesRunsOfNonAlphanumericCharactersIntoOneDash() {
        assertThat(OrganizationLoginKeys.normalize("Acme & Co., Ltd.")).isEqualTo("acme-co-ltd");
    }

    @Test
    void stripsLeadingAndTrailingDashes() {
        assertThat(OrganizationLoginKeys.normalize("!!!Acme!!!")).isEqualTo("acme");
    }

    @Test
    void keepsDigits() {
        assertThat(OrganizationLoginKeys.normalize("Acme 24x7 Corp")).isEqualTo("acme-24x7-corp");
    }

    @Test
    void resultIsAlwaysLowerCaseWithNoLeadingOrTrailingDash() {
        for (String name :
                new String[] {
                    "Acme Corp", "  spaced  ", "NUMBERS 123", "a", "A-B-C", "!!!Acme!!!"
                }) {
            String slug = OrganizationLoginKeys.normalize(name);
            assertThat(slug).as(name).matches(SHAPE);
            assertThat(slug).as(name).isEqualTo(slug.toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void resultNeverExceedsTwoHundredCharacters() {
        String longName = "A".repeat(500);

        assertThat(OrganizationLoginKeys.normalize(longName)).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void aNameWithNoLettersOrDigitsIsRejected() {
        assertThatThrownBy(() -> OrganizationLoginKeys.normalize("!!!###"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullNameIsRejected() {
        assertThatThrownBy(() -> OrganizationLoginKeys.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
