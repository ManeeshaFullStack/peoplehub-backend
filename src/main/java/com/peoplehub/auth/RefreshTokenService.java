package com.peoplehub.auth;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.auth.RefreshTokenStore.RevokeReason;
import com.peoplehub.auth.RefreshTokenStore.StoredRefreshToken;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.common.logging.OrganizationId;
import com.peoplehub.security.SecureTokens;
import com.peoplehub.security.jwt.AccessTokenIssuer;
import com.peoplehub.security.principal.PrincipalStatusQuery;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sessions as refresh-token families (b2-3, B2-3/5-B2-3/10).
 *
 * <ul>
 *   <li>{@link #start}: a login opens a new family with an absolute limit fixed there and then.
 *   <li>{@link #refresh}: every use rotates the token: the presented row is locked, revoked as
 *       {@code ROTATED} and replaced by a new row in the same family, whose sliding expiry is the
 *       earlier of now + the sliding window and the family's absolute limit.
 *   <li>Presenting a token that a refresh already replaced ({@code ROTATED}), or one presented
 *       after logout ({@code LOGOUT}: the browser that logged out no longer has it), is reuse: the
 *       whole family is revoked ({@code REUSE_DETECTED}) and the event is audited. There is no
 *       grace window, so two parallel refreshes of the same token end the session; the client must
 *       refresh one call at a time.
 *   <li>A token whose session was ended by a lifecycle action (a revoked session, a password reset
 *       or change, deactivation) is simply refused, as is one revoked by an earlier reuse
 *       detection: another device may still legitimately hold it, so it is not audited as reuse.
 *   <li>{@link #logout}: revokes the family ({@code LOGOUT}).
 * </ul>
 *
 * <p>A refresh is only granted while the employee and the organization are both {@code ACTIVE}.
 * Failure outcomes are returned, not thrown, so what was written before them (a reuse revocation
 * and its audit row) commits.
 */
@Service
public class RefreshTokenService {

    /** Revoke reasons under which presenting the token again is treated as theft (B2-3/8). */
    private static final Set<String> REUSE_REASONS =
            Set.of(RevokeReason.ROTATED.name(), RevokeReason.LOGOUT.name());

    private final RefreshTokenStore store;
    private final SecureTokens secureTokens;
    private final AccessTokenIssuer accessTokenIssuer;
    private final PrincipalStatusQuery statusQuery;
    private final AuditWriter auditWriter;
    private final Clock clock;
    private final Duration slidingTtl;
    private final Duration absoluteTtl;

    public RefreshTokenService(
            RefreshTokenStore store,
            SecureTokens secureTokens,
            AccessTokenIssuer accessTokenIssuer,
            PrincipalStatusQuery statusQuery,
            AuditWriter auditWriter,
            Clock clock,
            @Value("${peoplehub.auth.refresh-token.sliding-ttl}") Duration slidingTtl,
            @Value("${peoplehub.auth.refresh-token.absolute-ttl}") Duration absoluteTtl) {
        if (slidingTtl.isNegative()
                || slidingTtl.isZero()
                || absoluteTtl.compareTo(slidingTtl) < 0) {
            throw new IllegalStateException(
                    "peoplehub.auth.refresh-token: sliding-ttl must be positive and not longer"
                            + " than absolute-ttl");
        }
        this.store = store;
        this.secureTokens = secureTokens;
        this.accessTokenIssuer = accessTokenIssuer;
        this.statusQuery = statusQuery;
        this.auditWriter = auditWriter;
        this.clock = clock;
        this.slidingTtl = slidingTtl;
        this.absoluteTtl = absoluteTtl;
    }

    /**
     * Opens a new session (family) for a just-authenticated employee, in the caller's transaction.
     * {@code deviceLabel} is the coarse label from {@link DeviceLabels} (B2-6/3), never a raw
     * header; every rotated token of the family keeps it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    SessionTokens start(UUID organizationId, UUID employeeId, String role, String deviceLabel) {
        Instant now = clock.instant();
        Instant absoluteExpiresAt = now.plus(absoluteTtl);
        UUID family = UUID.randomUUID();
        String raw = secureTokens.generateRaw();
        Instant expiresAt = earlier(now.plus(slidingTtl), absoluteExpiresAt);
        store.insert(
                organizationId,
                employeeId,
                secureTokens.hash(raw),
                family,
                expiresAt,
                absoluteExpiresAt,
                deviceLabel);
        return new SessionTokens(
                family,
                accessTokenIssuer.issue(employeeId, organizationId, role, family),
                raw,
                expiresAt,
                secureTokens.generateRaw());
    }

    /** Rotates a refresh token; empty means "sign in again", whatever the reason (B2-3/12). */
    @Transactional
    public Optional<SessionTokens> refresh(String rawToken, InetAddress ip) {
        Optional<StoredRefreshToken> found = store.findForUpdate(secureTokens.hash(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        StoredRefreshToken token = found.get();
        Instant now = clock.instant();

        if (token.revoked()) {
            if (REUSE_REASONS.contains(token.revokeReason())) {
                // A token a refresh already replaced, or one whose browser logged out and dropped
                // it, has been presented again: it was copied. End the whole session, including
                // whoever holds its newest token.
                store.revokeFamily(token.familyId(), RevokeReason.REUSE_DETECTED, now);
                audit(token, "REFRESH_TOKEN_REUSE_DETECTED", ip);
            }
            // Any other revoked token belongs to a session that was ended on purpose.
            return Optional.empty();
        }
        if (!token.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        Optional<String> role =
                statusQuery.activeRole(
                        token.employeeId(), token.organizationId(), token.familyId());
        if (role.isEmpty()) {
            return Optional.empty();
        }

        String raw = secureTokens.generateRaw();
        Instant expiresAt = earlier(now.plus(slidingTtl), token.absoluteExpiresAt());
        UUID successor =
                store.insert(
                        token.organizationId(),
                        token.employeeId(),
                        secureTokens.hash(raw),
                        token.familyId(),
                        expiresAt,
                        token.absoluteExpiresAt(),
                        token.deviceLabel());
        store.revokeRotated(token.id(), successor, now);

        ActorId.set(token.employeeId().toString());
        OrganizationId.set(token.organizationId());
        return Optional.of(
                new SessionTokens(
                        token.familyId(),
                        accessTokenIssuer.issue(
                                token.employeeId(),
                                token.organizationId(),
                                role.get(),
                                token.familyId()),
                        raw,
                        expiresAt,
                        secureTokens.generateRaw()));
    }

    /**
     * Ends the session the refresh token belongs to (B2-3/9). An unknown or already revoked token
     * is not an error: logging out is idempotent.
     */
    @Transactional
    public void logout(String rawToken, InetAddress ip) {
        Optional<StoredRefreshToken> found = store.findForUpdate(secureTokens.hash(rawToken));
        if (found.isEmpty() || found.get().revoked()) {
            return;
        }
        StoredRefreshToken token = found.get();
        store.revokeFamily(token.familyId(), RevokeReason.LOGOUT, clock.instant());
        ActorId.set(token.employeeId().toString());
        OrganizationId.set(token.organizationId());
        audit(token, "LOGOUT", ip);
    }

    /**
     * Ends every session of an employee after a completed password reset (b2-5, B2-5/P13; Spec
     * 8.2): all their refresh tokens are revoked ({@code PASSWORD_RESET}), and so, through the
     * per-request session check (B2-3/14), are their access tokens. Returns how many tokens were
     * revoked. The caller audits the reset itself.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int endAllSessionsAfterPasswordReset(UUID organizationId, UUID employeeId) {
        return store.revokeEmployee(
                employeeId, organizationId, RevokeReason.PASSWORD_RESET, clock.instant());
    }

    /**
     * Ends every session of an employee being deactivated, or, as a safety net, one being
     * reactivated (b2-6, B2-6/11, B2-6/13; D26): all their refresh tokens are revoked ({@code
     * DEACTIVATED}), and with them, through the per-request session check (B2-3/14), their access
     * tokens. Returns how many tokens were revoked. The caller audits the change itself.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int endAllSessionsOnDeactivation(UUID organizationId, UUID employeeId) {
        return store.revokeEmployee(
                employeeId, organizationId, RevokeReason.DEACTIVATED, clock.instant());
    }

    /**
     * Ends every <em>other</em> session of an employee after a password change (b2-5, B2-5/P9; Spec
     * 8.2): their refresh tokens are revoked ({@code PASSWORD_CHANGED}) except those of the session
     * that made the change, which stays signed in. Returns how many tokens were revoked. The caller
     * audits the change itself.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int endOtherSessionsAfterPasswordChange(
            UUID organizationId, UUID employeeId, UUID currentSessionId) {
        return store.revokeEmployeeExceptFamily(
                employeeId,
                organizationId,
                currentSessionId,
                RevokeReason.PASSWORD_CHANGED,
                clock.instant());
    }

    private void audit(StoredRefreshToken token, String action, InetAddress ip) {
        auditWriter.append(
                AuditEvent.builder(token.organizationId(), action)
                        .target(AuditTarget.of("EMPLOYEE", token.employeeId().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("sessionId", token.familyId().toString())
                                        .build())
                        .build());
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
