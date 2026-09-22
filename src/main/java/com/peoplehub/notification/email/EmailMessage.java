package com.peoplehub.notification.email;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One email to enqueue (Spec 9.1, 9.2; b1-1). It carries only what the caller legitimately knows;
 * the row id, {@code status}, {@code attempts} and {@code created_at} are generated or defaulted by
 * the database (V4).
 *
 * <p>{@code organizationId} must be a real organization: never null and never the nil UUID. There
 * is deliberately no way to build a message without one, and no placeholder to use instead. Until
 * B2 creates organizations no production code can supply one, so nothing calls the writer yet --
 * the same position {@code AuditEvent}/{@code AuditWriter} were in after b0-6.
 *
 * <p>There is no employee reference: the spec's own {@code email_outbox} shape (Section 12)
 * addresses an email by recipient, not by an employee id, and B1 does not invent an employee table
 * or FK.
 *
 * @param organizationId the tenant the email belongs to
 * @param recipient the destination address
 * @param type UPPER_SNAKE_CASE event code, at most 64 characters, for example {@code
 *     EMPLOYEE_INVITED} (Spec 9.1)
 * @param payload template data; {@link EmailPayload#none()} when there is none
 */
public record EmailMessage(
        UUID organizationId, String recipient, String type, EmailPayload payload) {

    private static final UUID NIL = new UUID(0L, 0L);

    /**
     * Deliberately permissive (local@domain.tld shape only): this is a sanity check against garbage
     * input, not a full RFC 5321/5322 validator. The database only enforces non-blank (V4); this is
     * the stricter application-layer check the migration plan describes.
     */
    private static final Pattern RECIPIENT = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    static final Pattern TYPE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public EmailMessage {
        if (organizationId == null || NIL.equals(organizationId)) {
            throw new IllegalArgumentException(
                    "An email message needs a real organization id (not null, not the nil UUID)");
        }
        if (recipient == null || !RECIPIENT.matcher(recipient).matches()) {
            // The value is not echoed: recipient addresses are personal data.
            throw new IllegalArgumentException("Email recipient must look like an email address");
        }
        if (type == null || !TYPE_CODE.matcher(type).matches()) {
            throw new IllegalArgumentException("Email type must match " + TYPE_CODE.pattern());
        }
        if (payload == null) {
            payload = EmailPayload.none();
        }
    }

    public static Builder builder(UUID organizationId, String recipient, String type) {
        return new Builder(organizationId, recipient, type);
    }

    public static final class Builder {

        private final UUID organizationId;
        private final String recipient;
        private final String type;
        private EmailPayload payload = EmailPayload.none();

        private Builder(UUID organizationId, String recipient, String type) {
            this.organizationId = organizationId;
            this.recipient = recipient;
            this.type = type;
        }

        public Builder payload(EmailPayload payload) {
            this.payload = payload;
            return this;
        }

        public EmailMessage build() {
            return new EmailMessage(organizationId, recipient, type, payload);
        }
    }
}
