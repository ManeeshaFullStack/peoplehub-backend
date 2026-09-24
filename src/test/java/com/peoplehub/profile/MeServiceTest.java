package com.peoplehub.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The {@code firstName} rule (D17, Spec 10.3): one place, never re-parsed by the frontend. */
class MeServiceTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Riya Sharma|Riya",
                "  Riya   Sharma  |Riya",
                "Madonna|Madonna",
                "Jean-Luc Picard|Jean-Luc",
                "José Álvarez|José",
                "山田 太郎|山田",
                "Dr. Ada Lovelace|Dr."
            })
    void theFirstNameIsTheFirstWordOfTheName(String name, String firstName) {
        assertThat(MeService.firstName(name)).isEqualTo(firstName);
    }

    @Test
    void aNameWithTabsOrNewlinesStillSplitsOnWhitespace() {
        assertThat(MeService.firstName("Riya\tSharma")).isEqualTo("Riya");
    }
}
