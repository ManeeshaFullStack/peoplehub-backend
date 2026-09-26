package com.peoplehub.mfa;

import com.peoplehub.security.SecureTokens;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * An account's stored recovery codes (b2-7, B2-7/8; V11, V20 {@code mfa_recovery_code}): only
 * SHA-256 hashes of the normalized codes, bound to the organization and employee. Codes are never
 * deleted (the runtime role has no DELETE): a replaced set is made unusable with {@code
 * invalidated_at}, and a used code keeps its {@code used_at}. Runs in the caller's transaction.
 */
@Component
class RecoveryCodeStore {

    private static final String INVALIDATE_UNUSED =
            "UPDATE mfa_recovery_code SET invalidated_at = ?"
                    + " WHERE employee_id = ? AND organization_id = ?"
                    + " AND used_at IS NULL AND invalidated_at IS NULL";

    private static final String INSERT =
            "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash)"
                    + " VALUES (?, ?, ?)";

    private final JdbcClient jdbc;
    private final RecoveryCodes recoveryCodes;
    private final SecureTokens secureTokens;

    RecoveryCodeStore(JdbcClient jdbc, RecoveryCodes recoveryCodes, SecureTokens secureTokens) {
        this.jdbc = jdbc;
        this.recoveryCodes = recoveryCodes;
        this.secureTokens = secureTokens;
    }

    /** Makes every unused code unusable; returns how many. */
    @Transactional(propagation = Propagation.MANDATORY)
    int invalidateUnused(UUID organizationId, UUID employeeId, Timestamp at) {
        return jdbc.sql(INVALIDATE_UNUSED)
                .param(at)
                .param(employeeId)
                .param(organizationId)
                .update();
    }

    /** Invalidates the unused codes and stores a new set; returns the new codes, to show once. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<String> replace(UUID organizationId, UUID employeeId, Timestamp at) {
        invalidateUnused(organizationId, employeeId, at);
        List<String> codes = recoveryCodes.generate();
        for (String code : codes) {
            jdbc.sql(INSERT)
                    .param(organizationId)
                    .param(employeeId)
                    .param(secureTokens.hash(RecoveryCodes.normalize(code).orElseThrow()))
                    .update();
        }
        return codes;
    }
}
