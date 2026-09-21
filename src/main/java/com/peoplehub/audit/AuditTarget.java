package com.peoplehub.audit;

import java.util.regex.Pattern;

/**
 * What an audited action was done to: a type and, where one applies, an id.
 *
 * <p>The type is an UPPER_SNAKE_CASE code (at most 64 characters). The id is a token, {@code
 * [A-Za-z0-9._:-]{1,64}}: an identifier, never a name, an email or free text, so this cannot be
 * used to store personal data. Both formats are also enforced by check constraints on {@code
 * audit_log}.
 */
public record AuditTarget(String type, String id) {

    /** UPPER_SNAKE_CASE, at most 64 characters; the format of actions and target types. */
    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public AuditTarget {
        if (type == null || !CODE.matcher(type).matches()) {
            throw new IllegalArgumentException("Audit target type must match " + CODE.pattern());
        }
        if (id != null && !ID.matcher(id).matches()) {
            // The value is not echoed.
            throw new IllegalArgumentException("Audit target id must match " + ID.pattern());
        }
    }

    /** A target with an id, for example {@code of("EMPLOYEE", employeeId)}. */
    public static AuditTarget of(String type, String id) {
        if (id == null) {
            throw new IllegalArgumentException("Audit target id is required; use ofType(type)");
        }
        return new AuditTarget(type, id);
    }

    /** A target that has no single id, for example the {@code EMPLOYEE_LIST} an export covered. */
    public static AuditTarget ofType(String type) {
        return new AuditTarget(type, null);
    }
}
