package com.peoplehub.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Plain JDBC access to {@code refresh_token} (b2-3, V14), the same no-entity style as {@code
 * RegistrationService}. Only hashes are ever stored or looked up (B2-3/6). Rows are only inserted
 * and revoked, never rewritten or deleted (the runtime role could not do either anyway).
 */
@Component
class RefreshTokenStore {

    private static final String INSERT =
            "INSERT INTO refresh_token (organization_id, employee_id, token_hash, family_id,"
                    + " expires_at, absolute_expires_at, device_label)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id";

    private static final String SELECT_OWNER =
            "SELECT employee_id, organization_id FROM refresh_token WHERE token_hash = ?";

    private static final String SELECT_FOR_UPDATE =
            "SELECT id, organization_id, employee_id, family_id, expires_at, absolute_expires_at,"
                    + " revoked, revoke_reason, device_label FROM refresh_token WHERE token_hash = ?"
                    + " FOR UPDATE";

    private static final String REVOKE_ROTATED =
            "UPDATE refresh_token SET revoked = true, revoked_at = ?, revoke_reason = 'ROTATED',"
                    + " replaced_by_id = ? WHERE id = ? AND NOT revoked";

    private static final String REVOKE_FAMILY =
            "UPDATE refresh_token SET revoked = true, revoked_at = ?, revoke_reason = ?"
                    + " WHERE family_id = ? AND NOT revoked";

    private static final String REVOKE_EMPLOYEE =
            "UPDATE refresh_token SET revoked = true, revoked_at = ?, revoke_reason = ?"
                    + " WHERE employee_id = ? AND organization_id = ? AND NOT revoked";

    private static final String REVOKE_EMPLOYEE_EXCEPT_FAMILY =
            "UPDATE refresh_token SET revoked = true, revoked_at = ?, revoke_reason = ?"
                    + " WHERE employee_id = ? AND organization_id = ? AND family_id <> ?"
                    + " AND NOT revoked";

    private final JdbcClient jdbc;

    RefreshTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(
            UUID organizationId,
            UUID employeeId,
            String tokenHash,
            UUID familyId,
            Instant expiresAt,
            Instant absoluteExpiresAt,
            String deviceLabel) {
        return jdbc.sql(INSERT)
                .param(organizationId)
                .param(employeeId)
                .param(tokenHash)
                .param(familyId)
                .param(Timestamp.from(expiresAt))
                .param(Timestamp.from(absoluteExpiresAt))
                .param(deviceLabel)
                .query(UUID.class)
                .single();
    }

    /**
     * Whose token this hash is, read without a lock, so a refresh can lock the employee row before
     * the token row (the lock order {@link ActiveEmployeeLock} describes).
     */
    Optional<Owner> owner(String tokenHash) {
        return jdbc.sql(SELECT_OWNER)
                .param(tokenHash)
                .query(
                        (rs, rowNum) ->
                                new Owner(
                                        rs.getObject("employee_id", UUID.class),
                                        rs.getObject("organization_id", UUID.class)))
                .optional();
    }

    /**
     * The token with this hash, row-locked until the transaction ends, so two refreshes of the same
     * token run one after the other: the second one sees the first one's revocation (B2-3/7).
     */
    Optional<StoredRefreshToken> findForUpdate(String tokenHash) {
        return jdbc.sql(SELECT_FOR_UPDATE)
                .param(tokenHash)
                .query(
                        (rs, rowNum) ->
                                new StoredRefreshToken(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("organization_id", UUID.class),
                                        rs.getObject("employee_id", UUID.class),
                                        rs.getObject("family_id", UUID.class),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getTimestamp("absolute_expires_at").toInstant(),
                                        rs.getBoolean("revoked"),
                                        rs.getString("revoke_reason"),
                                        rs.getString("device_label")))
                .optional();
    }

    void revokeRotated(UUID id, UUID successorId, Instant at) {
        jdbc.sql(REVOKE_ROTATED).param(Timestamp.from(at)).param(successorId).param(id).update();
    }

    /** Revokes every still-usable token of a family; returns how many were revoked. */
    int revokeFamily(UUID familyId, RevokeReason reason, Instant at) {
        return jdbc.sql(REVOKE_FAMILY)
                .param(Timestamp.from(at))
                .param(reason.name())
                .param(familyId)
                .update();
    }

    /**
     * Revokes every still-usable token of one employee, in every family; returns how many were
     * revoked. Qualified by the organization as well as the employee (Spec 15.1).
     */
    int revokeEmployee(UUID employeeId, UUID organizationId, RevokeReason reason, Instant at) {
        return jdbc.sql(REVOKE_EMPLOYEE)
                .param(Timestamp.from(at))
                .param(reason.name())
                .param(employeeId)
                .param(organizationId)
                .update();
    }

    /**
     * Revokes every still-usable token of one employee except those of one family (the session that
     * asked); returns how many were revoked.
     */
    int revokeEmployeeExceptFamily(
            UUID employeeId,
            UUID organizationId,
            UUID keptFamilyId,
            RevokeReason reason,
            Instant at) {
        return jdbc.sql(REVOKE_EMPLOYEE_EXCEPT_FAMILY)
                .param(Timestamp.from(at))
                .param(reason.name())
                .param(employeeId)
                .param(organizationId)
                .param(keptFamilyId)
                .update();
    }

    /** Why a token was revoked; mirrors V19's {@code ck_refresh_token_revoke_reason}. */
    enum RevokeReason {
        ROTATED,
        LOGOUT,
        REUSE_DETECTED,
        PASSWORD_RESET,
        PASSWORD_CHANGED,
        SESSION_REVOKED,
        DEACTIVATED,
        // b2-7 (V19): MFA newly required of someone not enrolled; their MFA was reset; promotion.
        MFA_REQUIRED,
        MFA_RESET,
        ROLE_CHANGED
    }

    record Owner(UUID employeeId, UUID organizationId) {}

    record StoredRefreshToken(
            UUID id,
            UUID organizationId,
            UUID employeeId,
            UUID familyId,
            Instant expiresAt,
            Instant absoluteExpiresAt,
            boolean revoked,
            String revokeReason,
            String deviceLabel) {}
}
