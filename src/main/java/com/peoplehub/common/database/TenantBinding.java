package com.peoplehub.common.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes the tenant into a database transaction (b2-8; owner decision O1; B2-3/20): {@code SELECT
 * set_config('peoplehub.organization_id', <uuid>, true)}. The third argument makes the setting
 * <b>transaction-local</b>: PostgreSQL discards it at commit or rollback, so it can never survive
 * into the next use of a pooled connection. Nothing here ever sets it for a whole session.
 *
 * <p>{@link TenantTransactionManager} calls {@link #applyTo} as every transaction begins, from
 * {@link TenantContext}. {@link #bindCurrentTransaction} is for a flow that learns its organization
 * only after its transaction began (a public flow that first resolves a token or a login key); it
 * refuses to run outside a transaction, because a setting written outside one would last for a
 * single statement only.
 */
@Component
public class TenantBinding {

    /** The PostgreSQL setting that row-level security policies read. */
    public static final String SETTING = "peoplehub.organization_id";

    private static final String SET_LOCAL = "SELECT set_config('" + SETTING + "', ?, true)";

    private final DataSource dataSource;

    public TenantBinding(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Sets the tenant for the transaction currently open on {@code connection}. */
    public void applyTo(Connection connection, UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        try (PreparedStatement statement = connection.prepareStatement(SET_LOCAL)) {
            statement.setString(1, organizationId.toString());
            try (ResultSet ignored = statement.executeQuery()) {
                // set_config returns the new value; nothing to read.
            }
        } catch (SQLException e) {
            // No value, no SQL text: the cause is the driver's own, without the organization id.
            throw new IllegalStateException("Could not set the tenant for this transaction", e);
        }
    }

    /**
     * Sets the tenant for the transaction active on this thread (its connection, as the transaction
     * manager bound it).
     *
     * @throws IllegalStateException when no transaction is active
     */
    public void bindCurrentTransaction(UUID organizationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "The tenant can only be bound inside a transaction (O1: transaction-local only)");
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            applyTo(connection, organizationId);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
