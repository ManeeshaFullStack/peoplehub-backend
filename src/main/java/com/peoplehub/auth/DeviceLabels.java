package com.peoplehub.auth;

/**
 * A coarse, human-readable label for a session's device (b2-6, B2-6/3), such as "Chrome on
 * Windows", for the sessions list.
 *
 * <p>Derived from the {@code User-Agent} by a few built-in rules: browser family and operating
 * system only, nothing else (no versions, no device model). The result is always built from the
 * fixed words below, never from text of the header, so the raw {@code User-Agent} is never stored,
 * nothing a client sends can end up in the label, and the label is always well under {@value
 * #MAX_LENGTH} characters. An unknown or missing header gives {@value #UNKNOWN}. The IP is never
 * part of it. No external dependency.
 */
final class DeviceLabels {

    static final int MAX_LENGTH = 64;
    static final String UNKNOWN = "Unknown device";

    /** Only the start of the header is examined; browser and OS tokens come early. */
    private static final int MAX_EXAMINED = 512;

    private DeviceLabels() {}

    static String from(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String ua =
                userAgent.length() > MAX_EXAMINED
                        ? userAgent.substring(0, MAX_EXAMINED)
                        : userAgent;
        String browser = browser(ua);
        String os = os(ua);
        String label;
        if (browser != null && os != null) {
            label = browser + " on " + os;
        } else if (browser != null) {
            label = browser;
        } else if (os != null) {
            label = "Unknown browser on " + os;
        } else {
            label = UNKNOWN;
        }
        // Unreachable with the fixed words above; a guard in case a longer name is ever added.
        return label.length() > MAX_LENGTH ? UNKNOWN : label;
    }

    /** Order matters: most browsers also claim to be Chrome and Safari. */
    private static String browser(String ua) {
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) {
            return "Edge";
        }
        if (ua.contains("OPR/") || ua.contains("OPT/") || ua.contains("Opera")) {
            return "Opera";
        }
        if (ua.contains("SamsungBrowser/")) {
            return "Samsung Internet";
        }
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) {
            return "Firefox";
        }
        if (ua.contains("CriOS/") || ua.contains("Chrome/") || ua.contains("Chromium/")) {
            return "Chrome";
        }
        if (ua.contains("Safari/") && ua.contains("Version/")) {
            return "Safari";
        }
        return null;
    }

    /** Order matters: Android is also Linux, and iPad can claim to be a Mac. */
    private static String os(String ua) {
        if (ua.contains("Windows")) {
            return "Windows";
        }
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) {
            return "iOS";
        }
        if (ua.contains("Android")) {
            return "Android";
        }
        if (ua.contains("CrOS")) {
            return "ChromeOS";
        }
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) {
            return "macOS";
        }
        if (ua.contains("Linux")) {
            return "Linux";
        }
        return null;
    }
}
