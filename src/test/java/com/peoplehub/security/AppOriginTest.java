package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The configured app origin (b2-3, B2-3/11): validation and the Origin-header check. */
class AppOriginTest {

    @Test
    void unsetMeansNoOriginAndOnlyHeaderlessRequestsPass() {
        AppOrigin origin = new AppOrigin(null);

        assertThat(origin.value()).isEmpty();
        assertThat(origin.permits(null)).isTrue();
        assertThat(origin.permits("https://app.example.com")).isFalse();
        assertThat(new AppOrigin("  ").value()).isEmpty();
    }

    @Test
    void aConfiguredOriginIsNormalizedAndMatchedExactly() {
        AppOrigin origin = new AppOrigin(" https://App.Example.com ");

        assertThat(origin.value()).contains("https://app.example.com");
        assertThat(origin.permits("https://app.example.com")).isTrue();
        assertThat(origin.permits("HTTPS://APP.EXAMPLE.COM")).isTrue();
        assertThat(origin.permits(null)).isTrue();
        assertThat(origin.permits("https://app.example.com.evil.example")).isFalse();
        assertThat(origin.permits("http://app.example.com")).isFalse();
        assertThat(origin.permits("https://app.example.com:8443")).isFalse();
        assertThat(origin.permits("null")).isFalse();
    }

    @Test
    void anOriginWithAPortIsAccepted() {
        assertThat(new AppOrigin("http://localhost:3000").value())
                .contains("http://localhost:3000");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "app.example.com",
                "ftp://app.example.com",
                "https://app.example.com/",
                "https://app.example.com/path",
                "https://app.example.com?x=1",
                "https://user@app.example.com",
                "https://",
                "not a url"
            })
    void anythingButABareOriginStopsStartup(String configured) {
        assertThatThrownBy(() -> new AppOrigin(configured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PEOPLEHUB_SECURITY_APP_ORIGIN");
    }
}
