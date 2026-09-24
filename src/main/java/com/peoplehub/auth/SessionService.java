package com.peoplehub.auth;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.auth.RefreshTokenStore.RevokeReason;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.common.api.paging.PageQuery;
import com.peoplehub.common.api.paging.PageResponse;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in employee's own sessions (b2-6, B2-6/1, 2, 4, 5, 15, 16; Spec 8.2): list them, end
 * one, or end every other one. The employee and organization always come from the caller's token;
 * the only id taken from a request is the session to end, and it is looked up inside the caller's
 * own sessions, so another employee's or organization's session is simply "not found".
 */
@Service
public class SessionService {

    static final String NOT_FOUND = "This session was not found.";

    private final SessionStore store;
    private final AuditWriter auditWriter;
    private final Clock clock;

    public SessionService(SessionStore store, AuditWriter auditWriter, Clock clock) {
        this.store = store;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<SessionResponse> list(AuthenticatedPrincipal caller, PageQuery query) {
        Instant now = clock.instant();
        long total = store.countActive(caller.employeeId(), caller.organizationId(), now);
        List<SessionResponse> items =
                store.listActive(caller.employeeId(), caller.organizationId(), now, query).stream()
                        .map(
                                session ->
                                        new SessionResponse(
                                                session.sessionId(),
                                                session.sessionId().equals(caller.sessionId()),
                                                session.deviceLabel(),
                                                session.createdAt(),
                                                session.lastUsedAt(),
                                                session.expiresAt(),
                                                session.absoluteExpiresAt()))
                        .toList();
        return PageResponse.from(new PageImpl<>(items, query.toPageable(), total));
    }

    /**
     * Ends one of the caller's active sessions ({@code SESSION_REVOKED}); ending the current one
     * signs this device out. Returns whether it was the current session.
     */
    @Transactional
    public boolean revoke(AuthenticatedPrincipal caller, UUID sessionId, InetAddress ip) {
        boolean revoked =
                store.revokeActive(
                        sessionId,
                        caller.employeeId(),
                        caller.organizationId(),
                        RevokeReason.SESSION_REVOKED,
                        clock.instant());
        if (!revoked) {
            throw new ApiProblemException(ProblemType.NOT_FOUND, NOT_FOUND);
        }
        audit(
                caller,
                "SESSION_REVOKED",
                ip,
                AuditDetails.builder().attribute("sessionId", sessionId.toString()).build());
        return sessionId.equals(caller.sessionId());
    }

    /** Ends every active session of the caller except the one making the request. */
    @Transactional
    public void revokeOthers(AuthenticatedPrincipal caller, InetAddress ip) {
        int revoked =
                store.revokeOtherActive(
                        caller.sessionId(),
                        caller.employeeId(),
                        caller.organizationId(),
                        RevokeReason.SESSION_REVOKED,
                        clock.instant());
        audit(
                caller,
                "OTHER_SESSIONS_REVOKED",
                ip,
                AuditDetails.builder().attribute("revokedSessions", revoked).build());
    }

    private void audit(
            AuthenticatedPrincipal caller, String action, InetAddress ip, AuditDetails details) {
        auditWriter.append(
                AuditEvent.builder(caller.organizationId(), action)
                        .target(AuditTarget.of("EMPLOYEE", caller.employeeId().toString()))
                        .ip(ip)
                        .details(details)
                        .build());
    }
}
