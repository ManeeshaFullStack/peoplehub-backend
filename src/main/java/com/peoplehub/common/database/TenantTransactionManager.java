package com.peoplehub.common.database;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The application's transaction manager: Spring's {@link JpaTransactionManager}, unchanged, plus
 * one step right after a transaction begins (b2-8; owner decisions O1, O2; B2-3/20). When a {@link
 * TenantContext} is open on the thread, the tenant is written into the new transaction as a
 * transaction-local setting ({@link TenantBinding}), on the very connection that {@code JdbcClient}
 * and every other JDBC access in the transaction share. With no tenant open, nothing is set.
 *
 * <p>Transactions that join an existing one ({@code REQUIRED} inside a transaction) do not begin a
 * new one and so keep the tenant of the outer transaction; {@code REQUIRES_NEW} begins a new one on
 * another connection and applies the tenant current at that moment.
 */
public class TenantTransactionManager extends JpaTransactionManager {

    private final transient TenantBinding tenantBinding;

    public TenantTransactionManager(TenantBinding tenantBinding) {
        this.tenantBinding = tenantBinding;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        Optional<UUID> tenant = TenantContext.current();
        if (tenant.isEmpty()) {
            return;
        }
        DataSource dataSource = getDataSource();
        ConnectionHolder holder =
                dataSource == null
                        ? null
                        : (ConnectionHolder)
                                TransactionSynchronizationManager.getResource(dataSource);
        if (holder == null) {
            // Fail closed: a tenant is expected but the transaction exposes no JDBC connection.
            throw new IllegalStateException(
                    "The transaction exposes no JDBC connection to set the tenant on");
        }
        tenantBinding.applyTo(holder.getConnection(), tenant.get());
    }
}
