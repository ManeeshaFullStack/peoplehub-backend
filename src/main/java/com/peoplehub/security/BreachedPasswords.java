package com.peoplehub.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The offline breached-password list (b2-5, B2-5/P1): common passwords from public breach data,
 * bundled with the application as {@value #RESOURCE} and loaded once at startup. No outbound call
 * is ever made. Source, licence and how the file was built: {@code
 * security/breached-passwords.NOTICE.txt}.
 *
 * <p>The file holds only entries of 12 to 128 characters (anything else is already rejected by
 * length), lower-cased, so matching is an exact, case-insensitive comparison. A missing or empty
 * file stops the application at startup rather than silently disabling the check.
 */
@Component
public class BreachedPasswords {

    static final String RESOURCE = "security/breached-passwords.txt";

    private final Set<String> entries;

    public BreachedPasswords() {
        this(load());
    }

    BreachedPasswords(Set<String> entries) {
        this.entries = Set.copyOf(entries);
    }

    /** Whether the password (case-insensitively) is on the list. */
    public boolean contains(String rawPassword) {
        return rawPassword != null && entries.contains(rawPassword.toLowerCase(Locale.ROOT));
    }

    int size() {
        return entries.size();
    }

    private static Set<String> load() {
        InputStream stream = BreachedPasswords.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Breached-password list " + RESOURCE + " is missing");
        }
        Set<String> loaded = new HashSet<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    loaded.add(line.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Breached-password list " + RESOURCE + " is unreadable", e);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("Breached-password list " + RESOURCE + " is empty");
        }
        return loaded;
    }
}
