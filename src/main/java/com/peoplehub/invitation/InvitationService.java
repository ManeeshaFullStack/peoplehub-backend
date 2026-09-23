package com.peoplehub.invitation;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.notification.email.EmailMessage;
import com.peoplehub.notification.email.EmailOutboxWriter;
import com.peoplehub.notification.email.EmailPayload;
import com.peoplehub.organization.EmployeeCodes;
import com.peoplehub.security.SecureTokens;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating invitations (b2-4, B2-4 decisions; Spec 2.1.5, 3.3, D24, D25).
 *
 * <ul>
 *   <li>Who may invite (a minimal role check, not the b3-1 matrix): an Admin or Super Admin invites
 *       Employees; only a Super Admin invites Admins. Anyone else gets 403.
 *   <li>The organization is always the caller's own ({@link AuthenticatedPrincipal}); nothing in
 *       the request can choose another, and the role is fixed by the endpoint.
 *   <li>The employee row is created at invite time: {@code INVITED}, the invitation's role, no
 *       password (B2-4/O1). An {@code INVITED} employee with no open invitation is invited again on
 *       the same row, with the same role only; any other existing employee, or an open invitation,
 *       is a 409 (B2-4/O7).
 *   <li>The raw token exists only in the invitation email; only its hash is stored. The email is
 *       written to the outbox and the action audited, in the same transaction.
 * </ul>
 */
@Service
public class InvitationService {

    static final String EMPLOYEE = "EMPLOYEE";
    static final String ADMIN = "ADMIN";

    private static final String INVITATION_EMAIL = "EMPLOYEE_INVITED";

    /** {@code EmailPayload}'s token rule, mirrored (as {@code RegistrationService} does). */
    private static final Pattern EMAIL_TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private static final String DEFAULT_GREETING_NAME = "there";

    private final InvitationStore store;
    private final SecureTokens secureTokens;
    private final EmailOutboxWriter emailOutboxWriter;
    private final AuditWriter auditWriter;
    private final Clock clock;
    private final Duration ttl;
    private final String appName;

    public InvitationService(
            InvitationStore store,
            SecureTokens secureTokens,
            EmailOutboxWriter emailOutboxWriter,
            AuditWriter auditWriter,
            Clock clock,
            @Value("${peoplehub.invitation.ttl}") Duration ttl,
            @Value("${peoplehub.app-name}") String appName) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException("peoplehub.invitation.ttl must be positive");
        }
        this.store = store;
        this.secureTokens = secureTokens;
        this.emailOutboxWriter = emailOutboxWriter;
        this.auditWriter = auditWriter;
        this.clock = clock;
        this.ttl = ttl;
        this.appName = appName;
    }

    /** Invites a new Employee (Admin or Super Admin). */
    @Transactional
    public InvitationResponse inviteEmployee(AuthenticatedPrincipal caller, InviteRequest request) {
        if (!caller.isAdmin()) {
            throw forbidden();
        }
        return invite(caller, request, EMPLOYEE);
    }

    /** Invites a new person directly as Admin (Super Admin only, D25). */
    @Transactional
    public InvitationResponse inviteAdmin(AuthenticatedPrincipal caller, InviteRequest request) {
        if (!caller.isSuperAdmin()) {
            throw forbidden();
        }
        return invite(caller, request, ADMIN);
    }

    private InvitationResponse invite(
            AuthenticatedPrincipal caller, InviteRequest request, String role) {
        UUID organizationId = caller.organizationId();
        String emailNormalized = normalizeEmail(request.email());
        try {
            Optional<InvitationStore.Employee> existing =
                    store.employeeForUpdate(organizationId, emailNormalized);

            UUID employeeId;
            String recipient;
            String name;
            if (existing.isEmpty()) {
                String code =
                        request.employeeCode() != null
                                ? request.employeeCode()
                                : EmployeeCodes.generateSystemCode();
                if (store.employeeCodeExists(organizationId, code)) {
                    throw conflict("That employee code is already in use in your organization.");
                }
                employeeId =
                        store.insertInvitedEmployee(
                                organizationId,
                                code,
                                request.name(),
                                request.email(),
                                emailNormalized,
                                role,
                                request.joinDate());
                recipient = request.email();
                name = request.name();
            } else {
                InvitationStore.Employee employee = existing.get();
                if (!"INVITED".equals(employee.status())) {
                    throw conflict(
                            "An employee with this email already exists in your organization.");
                }
                if (!role.equals(employee.role())) {
                    throw conflict("This person has already been invited with a different role.");
                }
                if (store.hasOpenInvitation(organizationId, emailNormalized)) {
                    throw conflict(
                            "This person already has a pending invitation. Resend it instead.");
                }
                employeeId = employee.id();
                recipient = employee.email();
                name = employee.name();
            }

            IssuedInvitation issued =
                    issue(caller, employeeId, emailNormalized, role, recipient, name);
            audit(caller, issued.invitationId(), employeeId, role, "INVITATION_CREATED");
            return new InvitationResponse(
                    issued.invitationId(), employeeId, role, issued.expiresAt());
        } catch (DuplicateKeyException e) {
            // A concurrent invite for the same email or code won the race.
            throw conflict("This person or employee code was just invited. Refresh and try again.");
        }
    }

    /**
     * Creates the invitation row and its email: a fresh token whose raw value goes only into the
     * email payload. Shared with resend (b2-4 checkpoint 4).
     */
    IssuedInvitation issue(
            AuthenticatedPrincipal caller,
            UUID employeeId,
            String emailNormalized,
            String role,
            String recipient,
            String name) {
        String rawToken = secureTokens.generateRaw();
        Instant expiresAt = clock.instant().plus(ttl);
        UUID invitationId =
                store.insertInvitation(
                        caller.organizationId(),
                        emailNormalized,
                        role,
                        secureTokens.hash(rawToken),
                        caller.employeeId(),
                        expiresAt);
        InvitationStore.Organization organization = store.organization(caller.organizationId());
        emailOutboxWriter.enqueue(
                EmailMessage.builder(caller.organizationId(), recipient, INVITATION_EMAIL)
                        .payload(
                                EmailPayload.builder()
                                        .attribute("appName", appName)
                                        .attribute("firstName", emailSafeFirstName(name))
                                        .attribute("organizationLoginKey", organization.loginKey())
                                        .attribute("role", role)
                                        // "...Code", not "...Token": EmailPayload refuses keys
                                        // naming a token (B0-6/11's backstop). A future frontend
                                        // invitation link (/invite/{inviteCode}) is built from
                                        // this same value (B2-4/O10).
                                        .attribute("inviteCode", rawToken)
                                        .build())
                        .build());
        return new IssuedInvitation(invitationId, expiresAt);
    }

    void audit(
            AuthenticatedPrincipal caller,
            UUID invitationId,
            UUID employeeId,
            String role,
            String action) {
        auditWriter.append(
                AuditEvent.builder(caller.organizationId(), action)
                        .target(AuditTarget.of("EMPLOYEE_INVITATION", invitationId.toString()))
                        .details(
                                AuditDetails.builder()
                                        .attribute("employeeId", employeeId.toString())
                                        .attribute("role", role)
                                        .build())
                        .build());
    }

    record IssuedInvitation(UUID invitationId, Instant expiresAt) {}

    static ApiProblemException forbidden() {
        return new ApiProblemException(
                ProblemType.FORBIDDEN, "You are not allowed to perform this action.");
    }

    static ApiProblemException conflict(String detail) {
        return new ApiProblemException(ProblemType.CONFLICT, detail);
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String emailSafeFirstName(String fullName) {
        String firstToken = fullName.strip().split("\\s+", 2)[0];
        return EMAIL_TOKEN.matcher(firstToken).matches() ? firstToken : DEFAULT_GREETING_NAME;
    }
}
