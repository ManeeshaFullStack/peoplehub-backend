package com.peoplehub.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The password policy (b2-2, B2-2/2; b2-5, B2-5/P1, P2), the one policy used wherever a password is
 * set: registration, invitation acceptance and, from b2-5, reset and change. Length (12 to 128
 * characters), the offline breached-password list ({@link BreachedPasswords}), a check against
 * context terms (organization name, person's name, email local part), and a small blocklist of
 * well-known weak patterns. No forced character-class rule (uppercase/lowercase/digit/symbol mix):
 * length plus a breached list is the current evidence-based approach (NIST SP 800-63B), and a
 * composition rule tends to push people toward predictable substitutions. No external API call; a
 * k-anonymity breach API could be added later as another {@link PasswordSecurityValidator}.
 */
@Component
public class LocalPasswordSecurityValidator implements PasswordSecurityValidator {

    static final int MIN_LENGTH = 12;

    /** b2-5 (B2-5/P2): a generous cap that still bounds the work done on untrusted input. */
    static final int MAX_LENGTH = 128;

    private final BreachedPasswords breachedPasswords;

    public LocalPasswordSecurityValidator(BreachedPasswords breachedPasswords) {
        this.breachedPasswords = breachedPasswords;
    }

    /**
     * A context term shorter than this is ignored: a short term (for example a two-letter initials
     * field) would otherwise reject an unrelated password over a coincidental substring match.
     */
    private static final int MIN_CONTEXT_TERM_LENGTH = 3;

    /**
     * Well-known weak/obvious passwords and patterns (case-insensitive substring match against the
     * normalized password). Not a breached-password API or an exhaustive list -- a backstop against
     * the most obvious choices, per B2-2/2/B2-2/3's "reject obvious values" decision.
     */
    private static final Set<String> BLOCKLIST =
            Set.of(
                    "password",
                    "passw0rd",
                    "p@ssword",
                    "p@ssw0rd",
                    "12345678",
                    "123456789",
                    "1234567890",
                    "0123456789",
                    "qwertyuiop",
                    "qwerty123",
                    "asdfghjkl",
                    "letmein",
                    "welcome123",
                    "admin1234",
                    "changeme",
                    "iloveyou",
                    "monkey123",
                    "dragon123",
                    "sunshine1",
                    "princess1",
                    "football1",
                    "baseball1",
                    "superman1",
                    "trustno1",
                    "abc123456",
                    "000000000",
                    "111111111",
                    "aaaaaaaaa",
                    "hunter2222",
                    "starwars12");

    @Override
    public List<String> validate(String rawPassword, List<String> contextTerms) {
        List<String> violations = new ArrayList<>();
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            violations.add("Password must be at least " + MIN_LENGTH + " characters long.");
            // Every further check compares against the password's content; with none worth
            // trusting, stop here rather than pile on confusing follow-on messages.
            return violations;
        }
        if (rawPassword.length() > MAX_LENGTH) {
            violations.add("Password must be at most " + MAX_LENGTH + " characters long.");
            return violations;
        }
        if (breachedPasswords.contains(rawPassword)) {
            // A known-breached password is rejected outright; the other checks add nothing.
            violations.add("This password has appeared in a data breach. Choose a different one.");
            return violations;
        }

        String normalized = normalize(rawPassword);

        for (String term : contextTerms == null ? List.<String>of() : contextTerms) {
            if (term == null) {
                continue;
            }
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() < MIN_CONTEXT_TERM_LENGTH) {
                continue;
            }
            if (normalized.contains(normalizedTerm)) {
                violations.add("Password must not contain your name, email, or organization name.");
                break;
            }
        }

        for (String blocked : BLOCKLIST) {
            if (normalized.contains(blocked)) {
                violations.add("Password is too common or predictable. Choose a different one.");
                break;
            }
        }

        return violations;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
