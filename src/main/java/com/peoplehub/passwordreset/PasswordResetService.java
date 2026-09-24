package com.peoplehub.passwordreset;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.auth.RefreshTokenService;
import com.peoplehub.common.api.error.ApiFieldError;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.common.logging.OrganizationId;
import com.peoplehub.notification.email.EmailMessage;
import com.peoplehub.notification.email.EmailOutboxWriter;
import com.peoplehub.notification.email.EmailPayload;
import com.peoplehub.organization.OrganizationLoginKeys;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.security.PasswordSecurityValidator;
import com.peoplehub.security.SecureTokens;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgot and reset password (b2-5, B2-5/P6-P8, P11-P13; Spec 8.2, 13.0, 15.1).
 *
 * <ul>
 *   <li>{@link #forgot}: for an active employee of an active organization, and only while the
 *       throttle allows it (one per {@code min-interval}, {@code daily-limit} per 24 hours), older
 *       unused resets are invalidated, a new single-use code is issued (only its hash is stored),
 *       the reset email goes through the outbox and {@code PASSWORD_RESET_REQUESTED} is audited, in
 *       one transaction. Every other case (unknown organization or email, an account that is not
 *       active, a throttled request) does nothing, and the answer is always the same.
 *   <li>{@link #reset}: a usable code sets the new password (same policy as everywhere else),
 *       clears the lockout, uses the code up, ends every session of the employee ({@code
 *       PASSWORD_RESET}) and audits {@code PASSWORD_RESET_COMPLETED}, in one transaction. An
 *       unknown, expired, used or replaced code, or one whose account can no longer sign in, is one
 *       generic validation error.
 * </ul>
 *
 * <p>A known active account does slightly more database work in {@link #forgot} than an unknown
 * one; that timing difference is accepted (B2-5/P12). Admin-triggered resets do not exist (D24).
 */
@Service
public class PasswordResetService {

    static final String FORGOT_MESSAGE =
            "If an account matches those details, we've sent an email with a password reset code.";
    static final String INVALID_CODE = "is invalid or has expired";

    private static final String RESET_EMAIL = "PASSWORD_RESET";

    /** Far longer than a real code (64 hex characters); anything longer is not worth hashing. */
    private static final int MAX_TOKEN_LENGTH = 256;

    /** {@code EmailPayload}'s token rule, mirrored (as {@code InvitationService} does). */
    private static final Pattern EMAIL_TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private static final String DEFAULT_GREETING_NAME = "there";

    private final PasswordResetStore store;
    private final SecureTokens secureTokens;
    private final PasswordHasher passwordHasher;
    private final PasswordSecurityValidator passwordSecurityValidator;
    private final RefreshTokenService refreshTokenService;
    private final EmailOutboxWriter emailOutboxWriter;
    private final AuditWriter auditWriter;
    private final Clock clock;
    private final Duration ttl;
    private final Duration minInterval;
    private final int dailyLimit;
    private final String appName;

    public PasswordResetService(
            PasswordResetStore store,
            SecureTokens secureTokens,
            PasswordHasher passwordHasher,
            PasswordSecurityValidator passwordSecurityValidator,
            RefreshTokenService refreshTokenService,
            EmailOutboxWriter emailOutboxWriter,
            AuditWriter auditWriter,
            Clock clock,
            @Value("${peoplehub.auth.password-reset.ttl}") Duration ttl,
            @Value("${peoplehub.auth.password-reset.min-interval}") Duration minInterval,
            @Value("${peoplehub.auth.password-reset.daily-limit}") int dailyLimit,
            @Value("${peoplehub.app-name}") String appName) {
        if (ttl.isNegative()
                || ttl.isZero()
                || minInterval.isNegative()
                || minInterval.toSeconds() < 1
                || dailyLimit < 1) {
            throw new IllegalStateException(
                    "peoplehub.auth.password-reset: ttl and min-interval must be positive, and"
                            + " daily-limit at least 1");
        }
        this.store = store;
        this.secureTokens = secureTokens;
        this.passwordHasher = passwordHasher;
        this.passwordSecurityValidator = passwordSecurityValidator;
        this.refreshTokenService = refreshTokenService;
        this.emailOutboxWriter = emailOutboxWriter;
        this.auditWriter = auditWriter;
        this.clock = clock;
        this.ttl = ttl;
        this.minInterval = minInterval;
        this.dailyLimit = dailyLimit;
        this.appName = appName;
    }

    @Transactional
    public ForgotPasswordResponse forgot(ForgotPasswordRequest request, InetAddress ip) {
        loginKey(request.organization())
                .flatMap(key -> store.activeAccountForUpdate(key, normalizeEmail(request.email())))
                .filter(this::throttleAllows)
                .ifPresent(account -> issue(account, ip));
        return new ForgotPasswordResponse(FORGOT_MESSAGE);
    }

    @Transactional
    public void reset(ResetPasswordRequest request, InetAddress ip) {
        PasswordResetStore.Reset reset =
                tokenHash(request.token())
                        .flatMap(store::byTokenHashForUpdate)
                        .filter(found -> found.isUsableAt(clock.instant()))
                        .orElseThrow(
                                () ->
                                        validationFailure(
                                                List.of(new ApiFieldError("token", INVALID_CODE))));

        if (!request.password().equals(request.confirmPassword())) {
            throw validationFailure(
                    List.of(new ApiFieldError("confirmPassword", "must match password")));
        }
        List<String> violations =
                passwordSecurityValidator.validate(
                        request.password(),
                        List.of(
                                reset.organizationName(),
                                reset.employeeName(),
                                localPartOf(reset.employeeEmail())));
        if (!violations.isEmpty()) {
            throw validationFailure(
                    violations.stream().map(v -> new ApiFieldError("password", v)).toList());
        }

        boolean passwordSet =
                store.setPassword(
                        reset.employeeId(),
                        reset.organizationId(),
                        passwordHasher.hash(request.password()));
        boolean consumed = store.consume(reset.id(), clock.instant());
        if (!passwordSet || !consumed) {
            // Unreachable while the rows are locked; if it ever happens, nothing is committed.
            throw validationFailure(List.of(new ApiFieldError("token", INVALID_CODE)));
        }
        int sessionsEnded =
                refreshTokenService.endAllSessionsAfterPasswordReset(
                        reset.organizationId(), reset.employeeId());

        // Whoever holds the code controls the account's email: they are the actor of the reset.
        ActorId.set(reset.employeeId().toString());
        OrganizationId.set(reset.organizationId());
        auditWriter.append(
                AuditEvent.builder(reset.organizationId(), "PASSWORD_RESET_COMPLETED")
                        .target(AuditTarget.of("EMPLOYEE", reset.employeeId().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("resetId", reset.id().toString())
                                        .attribute("endedSessions", sessionsEnded)
                                        .build())
                        .build());
    }

    private boolean throttleAllows(PasswordResetStore.Account account) {
        PasswordResetStore.RecentRequests recent =
                store.recentRequests(account.employeeId(), account.organizationId(), minInterval);
        return recent.lastInterval() == 0 && recent.lastDay() < dailyLimit;
    }

    private void issue(PasswordResetStore.Account account, InetAddress ip) {
        Instant now = clock.instant();
        store.invalidateOpen(account.employeeId(), account.organizationId(), now);
        String rawToken = secureTokens.generateRaw();
        UUID resetId =
                store.insert(
                        account.organizationId(),
                        account.employeeId(),
                        secureTokens.hash(rawToken),
                        now.plus(ttl));
        emailOutboxWriter.enqueue(
                EmailMessage.builder(account.organizationId(), account.email(), RESET_EMAIL)
                        .payload(
                                EmailPayload.builder()
                                        .attribute("appName", appName)
                                        .attribute("firstName", emailSafeFirstName(account.name()))
                                        .attribute("organizationLoginKey", account.loginKey())
                                        // "...Code", not "...Token": EmailPayload refuses keys
                                        // naming a token (B0-6/11's backstop). A future frontend
                                        // reset link is built from this same value (B2-5/P6).
                                        .attribute("resetCode", rawToken)
                                        .attribute("expiryMinutes", ttl.toMinutes())
                                        .build())
                        .build());
        auditWriter.append(
                AuditEvent.builder(account.organizationId(), "PASSWORD_RESET_REQUESTED")
                        .target(AuditTarget.of("EMPLOYEE", account.employeeId().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("resetId", resetId.toString())
                                        .build())
                        .build());
    }

    /**
     * The organization field may be the login key or the organization's name, as on login. Input
     * with no letter or digit cannot be any organization's key.
     */
    private static Optional<String> loginKey(String organization) {
        try {
            return Optional.of(OrganizationLoginKeys.normalize(organization));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<String> tokenHash(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(secureTokens.hash(rawToken));
    }

    private static ApiProblemException validationFailure(List<ApiFieldError> errors) {
        return new ApiProblemException(
                ProblemType.VALIDATION_ERROR, "One or more fields are invalid.", errors, Map.of());
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String emailSafeFirstName(String fullName) {
        String firstToken = fullName.strip().split("\\s+", 2)[0];
        return EMAIL_TOKEN.matcher(firstToken).matches() ? firstToken : DEFAULT_GREETING_NAME;
    }

    private static String localPartOf(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
