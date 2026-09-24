package com.peoplehub.invitation;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.api.error.ApiFieldError;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.common.logging.OrganizationId;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.security.PasswordSecurityValidator;
import com.peoplehub.security.SecureTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public side of an invitation (b2-4, B2-4/O4, O5; Spec 2.1.5, D24).
 *
 * <ul>
 *   <li>{@link #preview} only reads: a mail scanner that follows the link cannot use it up.
 *   <li>{@link #accept} locks the invitation and its employee, checks the invitation is still
 *       usable, checks the invitee's own password with the same policy as registration, stores only
 *       its Argon2id hash, activates the employee and marks the invitation used, all in one
 *       transaction. It opens no session: the invitee then signs in normally (B2-4/O5).
 * </ul>
 *
 * <p>An unknown, expired, used or revoked token, one whose organization is not active, or one whose
 * employee is no longer the invited person all get the same generic 404 (B2-4/O4): a token either
 * works or it does not. The organization and role always come from the invitation, never from the
 * request.
 */
@Service
public class InvitationAcceptanceService {

    static final String INVALID = "This invitation is invalid or has expired.";

    /** Far longer than a real token (64 hex characters); anything longer is not worth hashing. */
    private static final int MAX_TOKEN_LENGTH = 256;

    private final InvitationStore store;
    private final SecureTokens secureTokens;
    private final PasswordHasher passwordHasher;
    private final PasswordSecurityValidator passwordSecurityValidator;
    private final AuditWriter auditWriter;
    private final Clock clock;

    public InvitationAcceptanceService(
            InvitationStore store,
            SecureTokens secureTokens,
            PasswordHasher passwordHasher,
            PasswordSecurityValidator passwordSecurityValidator,
            AuditWriter auditWriter,
            Clock clock) {
        this.store = store;
        this.secureTokens = secureTokens;
        this.passwordHasher = passwordHasher;
        this.passwordSecurityValidator = passwordSecurityValidator;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InvitationPreview preview(String rawToken) {
        InvitationStore.TokenInvitation invitation =
                usable(tokenHash(rawToken).flatMap(store::byTokenHash));
        return new InvitationPreview(
                invitation.organizationName(),
                invitation.role(),
                invitation.employeeName(),
                invitation.employeeEmail(),
                invitation.expiresAt());
    }

    @Transactional
    public void accept(String rawToken, AcceptInvitationRequest request) {
        InvitationStore.TokenInvitation invitation =
                usable(tokenHash(rawToken).flatMap(store::byTokenHashForUpdate));

        if (!request.password().equals(request.confirmPassword())) {
            throw validationFailure(
                    List.of(new ApiFieldError("confirmPassword", "must match password")));
        }
        List<String> violations =
                passwordSecurityValidator.validate(
                        request.password(),
                        List.of(
                                invitation.organizationName(),
                                invitation.employeeName(),
                                localPartOf(invitation.employeeEmail())));
        if (!violations.isEmpty()) {
            throw validationFailure(
                    violations.stream().map(v -> new ApiFieldError("password", v)).toList());
        }

        Instant now = clock.instant();
        boolean activated =
                store.activate(
                        invitation.employeeId(),
                        invitation.organizationId(),
                        passwordHasher.hash(request.password()),
                        now);
        boolean consumed = store.consume(invitation.id(), now);
        if (!activated || !consumed) {
            // Unreachable while the rows are locked; if it ever happens, nothing is committed.
            throw new ApiProblemException(ProblemType.NOT_FOUND, INVALID);
        }

        // The invitee is now the actor of their own activation.
        ActorId.set(invitation.employeeId().toString());
        OrganizationId.set(invitation.organizationId());
        auditWriter.append(
                AuditEvent.builder(invitation.organizationId(), "INVITATION_ACCEPTED")
                        .target(AuditTarget.of("EMPLOYEE_INVITATION", invitation.id().toString()))
                        .details(
                                AuditDetails.builder()
                                        .attribute("employeeId", invitation.employeeId().toString())
                                        .attribute("role", invitation.role())
                                        .build())
                        .build());
    }

    private InvitationStore.TokenInvitation usable(
            Optional<InvitationStore.TokenInvitation> found) {
        return found.filter(invitation -> invitation.isUsableAt(clock.instant()))
                .orElseThrow(() -> new ApiProblemException(ProblemType.NOT_FOUND, INVALID));
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

    private static String localPartOf(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
