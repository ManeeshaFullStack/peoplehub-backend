package com.peoplehub.auth;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.common.logging.OrganizationId;
import com.peoplehub.organization.OrganizationLoginKeys;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.security.SecureTokens;
import java.net.InetAddress;
import java.sql.Types;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password login (b2-3, B2-3/12, B2-3/13, B2-3/15, B2-3/17; Spec 2.1.6, 8.2): Organization +
 * company email + password, the same for every role, no role input (D27).
 *
 * <p>The organization is resolved by its login key first, and the account is then looked up only
 * inside that organization. Every way of failing (unknown organization, unknown email, wrong
 * password, no password set, an employee or organization that is not {@code ACTIVE}, which includes
 * a founder who has not verified their email) produces the same empty result, and every attempt
 * runs exactly one Argon2 verification: against a dummy hash when there is no account, so the
 * response takes as long either way.
 *
 * <p>Every attempt is recorded in {@code login_attempt} (what was typed, the address, the outcome);
 * nothing enforces lockout yet (b2-5). A successful login opens a session and is audited in the
 * same transaction. A failed one writes no audit row: it has no tenant to belong to (B0-6/16).
 *
 * <p>No MFA step exists (MFA/2): MFA is an organization policy built in b2-7.
 */
@Service
public class LoginService {

    private static final String SELECT_ACCOUNT =
            "SELECT e.id, e.organization_id, e.role, e.status AS employee_status,"
                    + " e.password_hash, o.status AS organization_status"
                    + " FROM organization o JOIN employee e ON e.organization_id = o.id"
                    + " WHERE o.login_key_normalized = ? AND e.email_normalized = ?";

    private static final String INSERT_ATTEMPT =
            "INSERT INTO login_attempt (organization_login_key_attempted, email_attempted, ip,"
                    + " success) VALUES (?, ?, CAST(? AS inet), ?)";

    private static final String ACTIVE = "ACTIVE";

    private final JdbcClient jdbc;
    private final PasswordHasher passwordHasher;
    private final RefreshTokenService refreshTokenService;
    private final AuditWriter auditWriter;

    /** Verified against when there is no account, so a miss costs the same as a wrong password. */
    private final String dummyHash;

    public LoginService(
            JdbcClient jdbc,
            PasswordHasher passwordHasher,
            SecureTokens secureTokens,
            RefreshTokenService refreshTokenService,
            AuditWriter auditWriter) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
        this.refreshTokenService = refreshTokenService;
        this.auditWriter = auditWriter;
        this.dummyHash = passwordHasher.hash(secureTokens.generateRaw());
    }

    /** Logs in; empty means "we couldn't sign you in with those details", whatever the reason. */
    @Transactional
    public Optional<SessionTokens> login(LoginRequest request, InetAddress ip) {
        Optional<Account> account =
                loginKey(request.organization())
                        .flatMap(key -> findAccount(key, normalizeEmail(request.email())));

        String hash = account.map(Account::passwordHash).orElse(null);
        boolean passwordMatches =
                passwordHasher.matches(request.password(), hash != null ? hash : dummyHash);
        boolean success =
                passwordMatches && hash != null && account.map(Account::isActive).orElse(false);

        recordAttempt(request, ip, success);
        if (!success) {
            return Optional.empty();
        }

        Account found = account.get();
        ActorId.set(found.employeeId().toString());
        OrganizationId.set(found.organizationId());
        SessionTokens tokens =
                refreshTokenService.start(found.organizationId(), found.employeeId(), found.role());
        auditWriter.append(
                AuditEvent.builder(found.organizationId(), "LOGIN_SUCCEEDED")
                        .target(AuditTarget.of("EMPLOYEE", found.employeeId().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("sessionId", tokens.sessionId().toString())
                                        .build())
                        .build());
        return Optional.of(tokens);
    }

    private Optional<Account> findAccount(String loginKey, String emailNormalized) {
        return jdbc.sql(SELECT_ACCOUNT)
                .param(loginKey)
                .param(emailNormalized)
                .query(
                        (rs, rowNum) ->
                                new Account(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("organization_id", UUID.class),
                                        rs.getString("role"),
                                        rs.getString("password_hash"),
                                        ACTIVE.equals(rs.getString("employee_status"))
                                                && ACTIVE.equals(
                                                        rs.getString("organization_status"))))
                .optional();
    }

    private void recordAttempt(LoginRequest request, InetAddress ip, boolean success) {
        jdbc.sql(INSERT_ATTEMPT)
                .param(request.organization())
                .param(request.email())
                .param(
                        new SqlParameterValue(
                                Types.VARCHAR, ip == null ? null : ip.getHostAddress()))
                .param(success)
                .update();
    }

    /**
     * The organization field may be the login key or the organization's name: both normalize to the
     * same key (registration derives the key from the name this way). Input with no letter or digit
     * cannot be any organization's key.
     */
    private static Optional<String> loginKey(String organization) {
        try {
            return Optional.of(OrganizationLoginKeys.normalize(organization));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private record Account(
            UUID employeeId,
            UUID organizationId,
            String role,
            String passwordHash,
            boolean isActive) {

        @Override
        public String toString() {
            return "Account[employeeId=" + employeeId + "]";
        }
    }
}
