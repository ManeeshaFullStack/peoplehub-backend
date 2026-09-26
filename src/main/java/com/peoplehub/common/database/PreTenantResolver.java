package com.peoplehub.common.database;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds the organization of a flow that starts without one, and binds the current transaction to it
 * (b2-8; owner decision O3; V24). Login, forgot password and resend verification start from an
 * organization login key; refresh, logout, email verification, password reset, invitations and the
 * MFA step of a sign-in start from a token. Each asks the matching V24 function, which answers one
 * organization id or nothing, then binds the transaction ({@link TenantBinding}) before the flow
 * reads or writes any tenant row, and the flow continues with its ordinary organization-qualified
 * SQL.
 *
 * <p>An empty answer binds nothing: the flow then takes its existing "not found" path, with the
 * same response as before, and touches no tenant row. Every method must run inside the flow's
 * transaction, because the binding is transaction-local (O1); outside one it throws.
 */
@Component
public class PreTenantResolver {

    private final JdbcClient jdbc;
    private final TenantBinding tenantBinding;

    public PreTenantResolver(JdbcClient jdbc, TenantBinding tenantBinding) {
        this.jdbc = jdbc;
        this.tenantBinding = tenantBinding;
    }

    /** By exact normalized login key (login, forgot password, resend verification). */
    public Optional<UUID> bindByLoginKey(String loginKeyNormalized) {
        return bind("peoplehub_organization_by_login_key", loginKeyNormalized);
    }

    /** By refresh-token hash (refresh, logout). */
    public Optional<UUID> bindByRefreshToken(String tokenHash) {
        return bind("peoplehub_organization_by_refresh_token", tokenHash);
    }

    /** By verification-token hash (founder email verification). */
    public Optional<UUID> bindByVerificationToken(String tokenHash) {
        return bind("peoplehub_organization_by_verification_token", tokenHash);
    }

    /** By reset-code hash (password reset). */
    public Optional<UUID> bindByPasswordResetToken(String tokenHash) {
        return bind("peoplehub_organization_by_password_reset_token", tokenHash);
    }

    /** By invitation-token hash (invitation preview and acceptance). */
    public Optional<UUID> bindByInvitationToken(String tokenHash) {
        return bind("peoplehub_organization_by_invitation_token", tokenHash);
    }

    /** By challenge-token hash (the MFA step of a sign-in). */
    public Optional<UUID> bindByMfaChallenge(String tokenHash) {
        return bind("peoplehub_organization_by_mfa_challenge", tokenHash);
    }

    /**
     * Binds a transaction to an organization the flow itself has just created (registration), so
     * its remaining work runs as that organization's.
     */
    public void bindCreated(UUID organizationId) {
        tenantBinding.bindCurrentTransaction(organizationId);
    }

    // The function name is one of the constants above, never input.
    private Optional<UUID> bind(String function, String value) {
        Optional<UUID> organizationId =
                jdbc.sql("SELECT " + function + "(?)").param(value).query(UUID.class).optional();
        organizationId.ifPresent(tenantBinding::bindCurrentTransaction);
        return organizationId;
    }
}
