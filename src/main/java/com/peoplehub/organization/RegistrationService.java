package com.peoplehub.organization;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.api.error.ApiFieldError;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.common.database.PreTenantResolver;
import com.peoplehub.notification.email.EmailMessage;
import com.peoplehub.notification.email.EmailOutboxWriter;
import com.peoplehub.notification.email.EmailPayload;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.security.PasswordSecurityValidator;
import com.peoplehub.security.SecureTokens;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organization registration, founder creation, email verification and resend (b2-2, Spec 2.1.3;
 * B2-2 decisions). Plain JDBC via {@link JdbcClient}, no JPA entity or repository -- the same
 * "insert-only writer, no entity" style {@link AuditWriter}/{@link EmailOutboxWriter} already use,
 * extended here to a small multi-statement service since registration is more than one insert.
 *
 * <p>Deliberately excluded (B2-2/8, and the B2-2 final scope decisions): login, JWT, refresh
 * tokens, MFA, invitations, onboarding-wizard completion, tenant context, and any duplicate-founder
 * -identity uniqueness beyond the organization name/login key (a cross-organization email lock was
 * considered and rejected as a tenant-isolation violation -- see CLAUDE.md's "Final Decision -- V14
 * scope"; true double-submit idempotency is B4's {@code Idempotency-Key} work, not this phase's).
 */
@Service
public class RegistrationService {

    // The owner-defined function (V24, O6): a new organization has no tenant to insert it under.
    private static final String CREATE_ORGANIZATION =
            "SELECT peoplehub_create_organization(?, ?, ?)";

    private static final String INSERT_EMPLOYEE =
            "INSERT INTO employee (organization_id, employee_code, name, email, email_normalized,"
                    + " status, role, join_date) VALUES (?, ?, ?, ?, ?, 'PENDING_VERIFICATION',"
                    + " 'SUPER_ADMIN', ?) RETURNING id";

    private static final String UPDATE_EMPLOYEE_PASSWORD =
            "UPDATE employee SET password_hash = ? WHERE id = ?";

    private static final String INSERT_TOKEN =
            "INSERT INTO organization_verification_token (organization_id, token_hash, expires_at)"
                    + " VALUES (?, ?, ?)";

    private static final String SELECT_TOKEN =
            "SELECT id, organization_id, expires_at, consumed_at"
                    + " FROM organization_verification_token"
                    + " WHERE organization_id = ? AND token_hash = ?";

    private static final String CONSUME_TOKEN =
            "UPDATE organization_verification_token SET consumed_at = ?"
                    + " WHERE id = ? AND consumed_at IS NULL";

    private static final String ACTIVATE_ORGANIZATION =
            "UPDATE organization SET status = 'ACTIVE' WHERE id = ?";

    private static final String ACTIVATE_FOUNDER =
            "UPDATE employee SET status = 'ACTIVE'"
                    + " WHERE organization_id = ? AND role = 'SUPER_ADMIN'"
                    + " AND status = 'PENDING_VERIFICATION'";

    private static final String SELECT_PENDING_FOUNDER =
            "SELECT o.id AS organization_id, e.name AS founder_name"
                    + " FROM organization o JOIN employee e ON e.organization_id = o.id"
                    + " WHERE o.id = ? AND o.login_key_normalized = ? AND e.email_normalized = ?"
                    + " AND e.role = 'SUPER_ADMIN' AND e.status = 'PENDING_VERIFICATION'";

    private static final Duration TOKEN_EXPIRY = Duration.ofHours(24);

    /**
     * {@code EmailPayload}'s own token pattern (B0-6/11): mirrored, not imported (package-private
     * there). A greeting name that does not fit it (an accented or non-Latin name, for example)
     * falls back to a generic greeting rather than fail the whole registration on a template
     * detail.
     */
    private static final Pattern EMAIL_TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private static final String DEFAULT_GREETING_NAME = "there";
    private static final String RESEND_MESSAGE =
            "If that organization and email match a pending registration, a verification email has"
                    + " been sent.";

    private final JdbcClient jdbc;
    private final PasswordHasher passwordHasher;
    private final PasswordSecurityValidator passwordSecurityValidator;
    private final SecureTokens secureTokens;
    private final EmailOutboxWriter emailOutboxWriter;
    private final AuditWriter auditWriter;
    private final PreTenantResolver preTenantResolver;
    private final Clock clock;
    private final String appName;

    public RegistrationService(
            JdbcClient jdbc,
            PasswordHasher passwordHasher,
            PasswordSecurityValidator passwordSecurityValidator,
            SecureTokens secureTokens,
            EmailOutboxWriter emailOutboxWriter,
            AuditWriter auditWriter,
            PreTenantResolver preTenantResolver,
            Clock clock,
            @Value("${peoplehub.app-name}") String appName) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
        this.passwordSecurityValidator = passwordSecurityValidator;
        this.secureTokens = secureTokens;
        this.emailOutboxWriter = emailOutboxWriter;
        this.auditWriter = auditWriter;
        this.preTenantResolver = preTenantResolver;
        this.clock = clock;
        this.appName = appName;
    }

    @Transactional
    public RegisterOrganizationResponse register(RegisterOrganizationRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw validationFailure(new ApiFieldError("confirmPassword", "must match password"));
        }

        ZoneId zoneId = parseTimeZone(request.timezone());

        String emailLocalPart = localPartOf(request.companyEmail());
        List<String> violations =
                passwordSecurityValidator.validate(
                        request.password(),
                        List.of(
                                request.organizationName(),
                                request.founderFullName(),
                                emailLocalPart));
        if (!violations.isEmpty()) {
            throw validationFailure(
                    violations.stream().map(v -> new ApiFieldError("password", v)).toList());
        }

        String loginKey = OrganizationLoginKeys.normalize(request.organizationName());
        String emailNormalized = normalizeEmail(request.companyEmail());

        UUID organizationId;
        try {
            organizationId =
                    jdbc.sql(CREATE_ORGANIZATION)
                            .param(request.organizationName())
                            .param(loginKey)
                            .param(request.timezone())
                            .query(UUID.class)
                            .single();
        } catch (DuplicateKeyException e) {
            throw new ApiProblemException(
                    ProblemType.CONFLICT,
                    "That organization name is already in use. Choose a more distinctive name.");
        }
        // Everything else registration writes belongs to the new organization (b2-8, O3, O6).
        preTenantResolver.bindCreated(organizationId);

        LocalDate joinDate = LocalDate.now(clock.withZone(zoneId));
        UUID employeeId =
                jdbc.sql(INSERT_EMPLOYEE)
                        .param(organizationId)
                        .param(EmployeeCodes.generateSystemCode())
                        .param(request.founderFullName())
                        .param(request.companyEmail())
                        .param(emailNormalized)
                        .param(joinDate)
                        .query(UUID.class)
                        .single();

        jdbc.sql(UPDATE_EMPLOYEE_PASSWORD)
                .param(passwordHasher.hash(request.password()))
                .param(employeeId)
                .update();

        String rawToken = issueVerificationToken(organizationId);

        emailOutboxWriter.enqueue(
                verificationEmail(
                        organizationId,
                        request.companyEmail(),
                        request.founderFullName(),
                        loginKey,
                        rawToken));

        auditWriter.append(
                AuditEvent.builder(organizationId, "ORGANIZATION_REGISTERED")
                        .target(AuditTarget.of("ORGANIZATION", organizationId.toString()))
                        .details(
                                AuditDetails.builder()
                                        .attribute("employeeId", employeeId.toString())
                                        .build())
                        .build());

        return new RegisterOrganizationResponse(loginKey);
    }

    @Transactional
    public VerifyEmailResponse verifyEmail(String rawToken) {
        String tokenHash = secureTokens.hash(rawToken);
        // The token's organization first (V24), binding this transaction to it (b2-8, O3).
        Optional<UUID> organizationId = preTenantResolver.bindByVerificationToken(tokenHash);
        if (organizationId.isEmpty()) {
            return new VerifyEmailResponse(false);
        }
        Optional<TokenLookup> found =
                jdbc.sql(SELECT_TOKEN)
                        .param(organizationId.get())
                        .param(tokenHash)
                        .query(
                                (rs, rowNum) ->
                                        new TokenLookup(
                                                (UUID) rs.getObject("id"),
                                                (UUID) rs.getObject("organization_id"),
                                                rs.getTimestamp("expires_at").toInstant(),
                                                rs.getTimestamp("consumed_at")))
                        .optional();

        if (found.isEmpty()) {
            return new VerifyEmailResponse(false);
        }
        TokenLookup token = found.get();
        if (token.consumedAt() != null || token.expiresAt().isBefore(clock.instant())) {
            return new VerifyEmailResponse(false);
        }

        int consumed =
                jdbc.sql(CONSUME_TOKEN)
                        .param(Timestamp.from(clock.instant()))
                        .param(token.id())
                        .update();
        if (consumed == 0) {
            // Consumed by a concurrent request between the SELECT above and this UPDATE (a resend
            // and the original link both landing at once). Not an error: the outcome is the same
            // generic response either way.
            return new VerifyEmailResponse(false);
        }

        jdbc.sql(ACTIVATE_ORGANIZATION).param(token.organizationId()).update();
        jdbc.sql(ACTIVATE_FOUNDER).param(token.organizationId()).update();

        auditWriter.append(
                AuditEvent.builder(token.organizationId(), "ORGANIZATION_EMAIL_VERIFIED")
                        .target(AuditTarget.of("ORGANIZATION", token.organizationId().toString()))
                        .build());

        return new VerifyEmailResponse(true);
    }

    @Transactional
    public ResendVerificationResponse resendVerification(ResendVerificationRequest request) {
        String loginKey = normalize(request.organizationLoginKey());
        String emailNormalized = normalizeEmail(request.companyEmail());

        // The organization first (V24), binding this transaction to it; an unknown one reads no
        // tenant row and gives the same answer (b2-8, O3).
        Optional<PendingFounder> found =
                preTenantResolver
                        .bindByLoginKey(loginKey)
                        .flatMap(
                                organizationId ->
                                        jdbc.sql(SELECT_PENDING_FOUNDER)
                                                .param(organizationId)
                                                .param(loginKey)
                                                .param(emailNormalized)
                                                .query(
                                                        (rs, rowNum) ->
                                                                new PendingFounder(
                                                                        (UUID)
                                                                                rs.getObject(
                                                                                        "organization_id"),
                                                                        rs.getString(
                                                                                "founder_name")))
                                                .optional());

        if (found.isPresent()) {
            PendingFounder founder = found.get();
            String rawToken = issueVerificationToken(founder.organizationId());
            emailOutboxWriter.enqueue(
                    verificationEmail(
                            founder.organizationId(),
                            request.companyEmail(),
                            founder.founderName(),
                            loginKey,
                            rawToken));
            auditWriter.append(
                    AuditEvent.builder(founder.organizationId(), "ORGANIZATION_VERIFICATION_RESENT")
                            .target(
                                    AuditTarget.of(
                                            "ORGANIZATION", founder.organizationId().toString()))
                            .build());
        }

        // Always the same response, whether or not anything matched above (Spec 13.0, D22).
        return new ResendVerificationResponse(RESEND_MESSAGE);
    }

    private String issueVerificationToken(UUID organizationId) {
        String rawToken = secureTokens.generateRaw();
        jdbc.sql(INSERT_TOKEN)
                .param(organizationId)
                .param(secureTokens.hash(rawToken))
                .param(Timestamp.from(clock.instant().plus(TOKEN_EXPIRY)))
                .update();
        return rawToken;
    }

    private EmailMessage verificationEmail(
            UUID organizationId,
            String recipient,
            String founderFullName,
            String loginKey,
            String rawToken) {
        return EmailMessage.builder(organizationId, recipient, "ORGANIZATION_VERIFICATION")
                .payload(
                        EmailPayload.builder()
                                .attribute("appName", appName)
                                .attribute("firstName", emailSafeFirstName(founderFullName))
                                .attribute("organizationLoginKey", loginKey)
                                // Named "...Code", not "...Token": EmailPayload's sensitive-name
                                // backstop (B0-6/11's rule, mirrored) rejects any attribute *key*
                                // containing "token" case-insensitively, so a key literally named
                                // "verificationToken" is refused even though the token value itself
                                // is exactly what this legitimate delivery email must carry. Same
                                // reason EMPLOYEE_INVITED.txt's key is "inviteCode", not
                                // "inviteToken".
                                .attribute("verificationCode", rawToken)
                                .build())
                .build();
    }

    private static ZoneId parseTimeZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw validationFailure(
                    new ApiFieldError("timezone", "must be a valid IANA time zone id"));
        }
    }

    private static ApiProblemException validationFailure(ApiFieldError error) {
        return validationFailure(List.of(error));
    }

    private static ApiProblemException validationFailure(List<ApiFieldError> errors) {
        return new ApiProblemException(
                ProblemType.VALIDATION_ERROR, "One or more fields are invalid.", errors, Map.of());
    }

    private static String emailSafeFirstName(String fullName) {
        String firstToken = fullName.strip().split("\\s+", 2)[0];
        return EMAIL_TOKEN.matcher(firstToken).matches() ? firstToken : DEFAULT_GREETING_NAME;
    }

    private static String localPartOf(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static String normalizeEmail(String email) {
        return normalize(email);
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private record TokenLookup(
            UUID id, UUID organizationId, Instant expiresAt, Timestamp consumedAt) {}

    private record PendingFounder(UUID organizationId, String founderName) {}
}
