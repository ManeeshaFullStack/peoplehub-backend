package com.peoplehub.mfa;

import com.peoplehub.security.SecureTokens;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The MFA step between a correct password and a session (b2-7, B2-7/9, B2-7/10, B2-7/22; V21 {@code
 * mfa_challenge}).
 *
 * <p>A challenge token is 256 random bits, returned once by the login answer and stored only as its
 * SHA-256 hash. It is single use: completing it sets {@code consumed_at}; too many wrong codes, a
 * refused completion or an inactive account set {@code invalidated_at}. Rows are never deleted.
 * {@link Purpose#CHALLENGE} lives {@value #CHALLENGE_MINUTES} minutes, {@link Purpose#ENROLL}
 * {@value #ENROLL_MINUTES} (B2-7/9); {@value #MAX_FAILED_ATTEMPTS} wrong codes invalidate a
 * challenge (B2-7/10).
 *
 * <p>The device label and address of the password step are kept for the session and for audit only:
 * completing a challenge from another address is never refused. Every query is qualified by the
 * token hash, or by the id of a challenge found by it, and runs in the caller's transaction.
 */
@Component
public class MfaChallenges {

    static final int CHALLENGE_MINUTES = 5;
    static final int ENROLL_MINUTES = 10;
    public static final int MAX_FAILED_ATTEMPTS = 5;

    /** Why the password step did not open a session. */
    public enum Purpose {
        /** The person is enrolled: prove a TOTP or recovery code. */
        CHALLENGE,
        /** The policy requires MFA of the person, who is not enrolled: enroll first. */
        ENROLL
    }

    /** An open challenge: not consumed, not invalidated, not expired. */
    public record Challenge(
            UUID id, UUID organizationId, UUID employeeId, Purpose purpose, String deviceLabel) {}

    private static final String INSERT =
            "INSERT INTO mfa_challenge (organization_id, employee_id, token_hash, purpose,"
                    + " device_label, ip, expires_at) VALUES (?, ?, ?, ?, ?, CAST(? AS inet), ?)";

    private static final String SELECT_OPEN =
            "SELECT id, organization_id, employee_id, purpose, device_label FROM mfa_challenge"
                    + " WHERE %s AND purpose = ? AND consumed_at IS NULL"
                    + " AND invalidated_at IS NULL AND expires_at > ?";

    private static final String CONSUME =
            "UPDATE mfa_challenge SET consumed_at = ?"
                    + " WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL";

    private static final String INVALIDATE =
            "UPDATE mfa_challenge SET invalidated_at = ?"
                    + " WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL";

    private static final String INVALIDATE_OPEN =
            "UPDATE mfa_challenge SET invalidated_at = ?"
                    + " WHERE organization_id = ? AND employee_id = ?"
                    + " AND consumed_at IS NULL AND invalidated_at IS NULL";

    private static final String COUNT_FAILURE =
            "UPDATE mfa_challenge SET failed_attempts = failed_attempts + 1"
                    + " WHERE id = ? RETURNING failed_attempts";

    private final JdbcClient jdbc;
    private final SecureTokens secureTokens;
    private final Clock clock;

    public MfaChallenges(JdbcClient jdbc, SecureTokens secureTokens, Clock clock) {
        this.jdbc = jdbc;
        this.secureTokens = secureTokens;
        this.clock = clock;
    }

    /** Creates a challenge and returns its raw token, which is never stored. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String create(
            UUID organizationId,
            UUID employeeId,
            Purpose purpose,
            String deviceLabel,
            InetAddress ip) {
        String raw = secureTokens.generateRaw();
        Duration ttl =
                Duration.ofMinutes(
                        purpose == Purpose.CHALLENGE ? CHALLENGE_MINUTES : ENROLL_MINUTES);
        jdbc.sql(INSERT)
                .param(organizationId)
                .param(employeeId)
                .param(secureTokens.hash(raw))
                .param(purpose.name())
                .param(deviceLabel)
                .param(
                        new SqlParameterValue(
                                Types.VARCHAR, ip == null ? null : ip.getHostAddress()))
                .param(Timestamp.from(clock.instant().plus(ttl)))
                .update();
        return raw;
    }

    /** The open challenge of this purpose the raw token names, without a lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Challenge> findOpen(String rawToken, Purpose purpose) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return select("token_hash = ?", secureTokens.hash(rawToken), purpose, false);
    }

    /**
     * The same challenge again, locked {@code FOR UPDATE} and still open. Take the employee row's
     * lock first (the lock order of login, refresh and deactivation, B2-6/12).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Challenge> lockOpen(Challenge challenge) {
        return select("id = ?", challenge.id(), challenge.purpose(), true);
    }

    /** Marks the challenge completed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Challenge challenge) {
        jdbc.sql(CONSUME).param(now()).param(challenge.id()).update();
    }

    /** Makes the challenge unusable. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidate(Challenge challenge) {
        jdbc.sql(INVALIDATE).param(now()).param(challenge.id()).update();
    }

    /**
     * Makes every open challenge of an account unusable (its MFA was disabled or reset); returns
     * how many. The completion-time re-check would refuse them anyway; this is defense in depth.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int invalidateOpen(UUID organizationId, UUID employeeId) {
        return jdbc.sql(INVALIDATE_OPEN)
                .param(now())
                .param(organizationId)
                .param(employeeId)
                .update();
    }

    /**
     * Counts one wrong code; at {@value #MAX_FAILED_ATTEMPTS} the challenge is invalidated.
     *
     * @return whether the challenge is still usable
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordWrongCode(Challenge challenge) {
        int failures = jdbc.sql(COUNT_FAILURE).param(challenge.id()).query(Integer.class).single();
        if (failures >= MAX_FAILED_ATTEMPTS) {
            invalidate(challenge);
            return false;
        }
        return true;
    }

    private Optional<Challenge> select(String where, Object key, Purpose purpose, boolean lock) {
        return jdbc.sql(String.format(SELECT_OPEN, where) + (lock ? " FOR UPDATE" : ""))
                .param(key)
                .param(purpose.name())
                .param(now())
                .query(
                        (rs, rowNum) ->
                                new Challenge(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("organization_id", UUID.class),
                                        rs.getObject("employee_id", UUID.class),
                                        Purpose.valueOf(rs.getString("purpose")),
                                        rs.getString("device_label")))
                .optional();
    }

    private Timestamp now() {
        Instant now = clock.instant();
        return Timestamp.from(now);
    }
}
