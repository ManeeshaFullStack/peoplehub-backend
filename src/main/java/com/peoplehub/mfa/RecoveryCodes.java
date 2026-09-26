package com.peoplehub.mfa;

import com.peoplehub.security.SecureTokens;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * MFA recovery codes (b2-7, B2-7/8): ten single-use codes per enrollment, each 80 random bits
 * written as 16 base32 characters in four groups of four ({@code ABCD-EFGH-JKLM-NPQR}). They are
 * shown once and stored only as SHA-256 hashes ({@link SecureTokens#hash}); 80 random bits need no
 * slow hash.
 *
 * <p>A submitted code is normalized before hashing (case, spaces and dashes ignored), so it matches
 * however the person types it. Anything that is not exactly 16 base32 characters after that is
 * rejected without being hashed.
 */
@Component
public class RecoveryCodes {

    public static final int COUNT = 10;

    static final int CODE_BYTES = 10;
    static final int CODE_CHARACTERS = 16;
    private static final int GROUP = 4;

    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]+");
    private static final Pattern NORMALIZED = Pattern.compile("[A-Z2-7]{" + CODE_CHARACTERS + "}");

    private final SecureTokens secureTokens;
    private final SecureRandom random = new SecureRandom();

    public RecoveryCodes(SecureTokens secureTokens) {
        this.secureTokens = secureTokens;
    }

    /** Ten new codes, formatted for display. Never store them; store {@link #hash} of each. */
    public List<String> generate() {
        List<String> codes = new ArrayList<>(COUNT);
        while (codes.size() < COUNT) {
            byte[] bytes = new byte[CODE_BYTES];
            random.nextBytes(bytes);
            String code = format(Base32.encode(bytes));
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    /** The stored hash of a submitted code; empty when it cannot be a recovery code. */
    public Optional<String> hash(String submitted) {
        return normalize(submitted).map(secureTokens::hash);
    }

    /** The canonical form: 16 upper-case base32 characters without separators. */
    static Optional<String> normalize(String submitted) {
        if (submitted == null) {
            return Optional.empty();
        }
        String code = SEPARATORS.matcher(submitted).replaceAll("").toUpperCase(Locale.ROOT);
        return NORMALIZED.matcher(code).matches() ? Optional.of(code) : Optional.empty();
    }

    private static String format(String code) {
        StringBuilder out = new StringBuilder(CODE_CHARACTERS + CODE_CHARACTERS / GROUP - 1);
        for (int i = 0; i < code.length(); i += GROUP) {
            if (i > 0) {
                out.append('-');
            }
            out.append(code, i, i + GROUP);
        }
        return out.toString();
    }
}
