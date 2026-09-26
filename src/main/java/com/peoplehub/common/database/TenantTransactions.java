package com.peoplehub.common.database;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a piece of database work in its own transaction bound to one organization (b2-8, owner
 * decisions O1, O4): the organization is opened in {@link TenantContext} for the duration, so
 * {@link TenantTransactionManager} writes it into the transaction as it begins. For work whose
 * organization is known from a trusted server-side source outside any request context, such as the
 * verified token of the per-request status check or the row a job is processing.
 *
 * <p>Each call begins a new transaction ({@code REQUIRES_NEW}), so the tenant can never be taken
 * from, or leak into, a transaction the caller may already have open.
 */
@Component
public class TenantTransactions {

    private final TransactionTemplate readWrite;
    private final TransactionTemplate readOnly;

    public TenantTransactions(PlatformTransactionManager transactionManager) {
        this.readWrite = new TransactionTemplate(transactionManager);
        this.readWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
    }

    /** Runs {@code work} in a new read-write transaction for {@code organizationId}. */
    public <T> T inTransaction(UUID organizationId, Supplier<T> work) {
        return run(readWrite, organizationId, work);
    }

    /** Runs {@code work} in a new read-only transaction for {@code organizationId}. */
    public <T> T inReadOnlyTransaction(UUID organizationId, Supplier<T> work) {
        return run(readOnly, organizationId, work);
    }

    private static <T> T run(TransactionTemplate template, UUID organizationId, Supplier<T> work) {
        try (TenantContext.Scope scope = TenantContext.open(organizationId)) {
            return template.execute(status -> work.get());
        }
    }
}
