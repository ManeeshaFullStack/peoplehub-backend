package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The coarse device label (b2-6, B2-6/3): browser family and operating system only, built from
 * fixed words, never from the header's own text, at most 64 characters, "Unknown device" when
 * nothing is recognised.
 */
class DeviceLabelsTest {

    /** Every word a label may be built from; nothing else can ever appear. */
    private static final Set<String> VOCABULARY =
            Set.of(
                    "Edge",
                    "Opera",
                    "Samsung",
                    "Internet",
                    "Firefox",
                    "Chrome",
                    "Safari",
                    "Unknown",
                    "browser",
                    "device",
                    "on",
                    "Windows",
                    "iOS",
                    "Android",
                    "ChromeOS",
                    "macOS",
                    "Linux");

    private static void assertSafe(String label) {
        assertThat(label).isNotBlank().hasSizeLessThanOrEqualTo(DeviceLabels.MAX_LENGTH);
        assertThat(List.of(label.split(" ")))
                .allSatisfy(word -> assertThat(VOCABULARY).contains(word));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/128.0.0.0 Safari/537.36|Chrome on Windows",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0|Edge on Windows",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101"
                        + " Firefox/130.0|Firefox on Windows",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like"
                        + " Gecko) Version/17.6 Safari/605.1.15|Safari on macOS",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/128.0.0.0 Safari/537.36 OPR/113.0.0.0|Opera on macOS",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/128.0.0.0 Safari/537.36|Chrome on Linux",
                "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:130.0) Gecko/20100101"
                        + " Firefox/130.0|Firefox on Linux",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/128.0.0.0 Mobile Safari/537.36|Chrome on Android",
                "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36"
                        + "|Samsung Internet on Android",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15"
                        + " (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
                        + "|Safari on iOS",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15"
                        + " (KHTML, like Gecko) CriOS/128.0.6613.98 Mobile/15E148 Safari/604.1"
                        + "|Chrome on iOS",
                "Mozilla/5.0 (iPad; CPU OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like"
                        + " Gecko) FxiOS/130.0 Mobile/15E148 Safari/605.1.15|Firefox on iOS",
                "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/128.0.0.0 Safari/537.36|Chrome on ChromeOS",
                "curl/8.9.1|Unknown device",
                "PostmanRuntime/7.41.2|Unknown device",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64)|Unknown browser on Windows",
            })
    void commonBrowsersGetACoarseLabel(String userAgent, String expected) {
        String label = DeviceLabels.from(userAgent);

        assertThat(label).isEqualTo(expected);
        assertSafe(label);
    }

    @Test
    void aMissingOrBlankHeaderIsUnknown() {
        assertThat(DeviceLabels.from(null)).isEqualTo(DeviceLabels.UNKNOWN);
        assertThat(DeviceLabels.from("")).isEqualTo(DeviceLabels.UNKNOWN);
        assertThat(DeviceLabels.from("   \t ")).isEqualTo(DeviceLabels.UNKNOWN);
    }

    @Test
    void nothingTheClientSendsEndsUpInTheLabel() {
        List<String> hostile =
                List.of(
                        "Chrome/1 <script>alert(1)</script> Windows",
                        "Firefox/1'; DROP TABLE refresh_token; -- Linux",
                        "Safari/1 Version/1 jane.doe@example.com 10.0.0.7 Macintosh",
                        "Chrome/1 \r\nSet-Cookie: x=y\r\n Android",
                        // NUL, right-to-left override, zero-width space, built from code points so
                        // no invisible character is ever written into this source file.
                        "Edg/1 "
                                + (char) 0x0000
                                + (char) 0x202E
                                + (char) 0x200B
                                + " Windows ${jndi:ldap://x}",
                        "{{appName}} Chrome/1 %s %n Windows");

        for (String userAgent : hostile) {
            String label = DeviceLabels.from(userAgent);
            assertSafe(label);
            assertThat(label)
                    .doesNotContain("<")
                    .doesNotContain("'")
                    .doesNotContain("@")
                    .doesNotContain("10.0.0.7")
                    .doesNotContain("\n")
                    .doesNotContain("$")
                    .doesNotContain("{")
                    .doesNotContain("%");
        }
    }

    @Test
    void anOversizedHeaderStillGivesAShortLabel() {
        String huge = "Mozilla/5.0 (Windows NT 10.0) Chrome/128.0 " + "x".repeat(100_000);

        String label = DeviceLabels.from(huge);

        assertThat(label).isEqualTo("Chrome on Windows");
        assertSafe(label);
    }

    @Test
    void onlyTheStartOfAnOversizedHeaderIsExamined() {
        // Browser and OS tokens always come early; text far into a huge header is ignored.
        String late = "x".repeat(10_000) + " Chrome/128.0 Windows";

        assertThat(DeviceLabels.from(late)).isEqualTo(DeviceLabels.UNKNOWN);
    }

    @Test
    void theLongestPossibleLabelFitsTheLimit() {
        String longest = "Samsung Internet on ChromeOS";

        assertThat(longest.length()).isLessThanOrEqualTo(DeviceLabels.MAX_LENGTH);
        assertThat(DeviceLabels.from("SamsungBrowser/25.0 CrOS")).isEqualTo(longest);
    }
}
