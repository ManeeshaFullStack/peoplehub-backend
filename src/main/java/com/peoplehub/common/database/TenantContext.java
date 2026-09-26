package com.peoplehub.common.database;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The organization the current thread's database work belongs to (b2-8; Spec D30, 12.1, 15.1;
 * B2-3/20): the source of the transaction-local PostgreSQL tenant setting {@value
 * TenantBinding#SETTING}, which row-level security policies read.
 *
 * <p>It is only ever opened from a trusted, server-side source: the verified access token's
 * principal for an authenticated request, a value resolved inside the server for a public flow, or
 * the organization of a row a job is processing. Never from anything a client sends.
 *
 * <p>This is an in-memory hint, not the database state: {@link TenantTransactionManager} copies it
 * into each transaction as it begins, and the setting disappears when that transaction ends. Open
 * it with try-with-resources so it is restored on every path:
 *
 * <pre>{@code
 * try (TenantContext.Scope scope = TenantContext.open(organizationId)) {
 *     ... transactional work for that organization ...
 * }
 * }</pre>
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    /** The organization of the current thread's database work, if one is open. */
    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Makes {@code organizationId} the current organization until the returned scope is closed,
     * which restores whatever was current before (nesting is allowed).
     */
    public static Scope open(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        UUID previous = CURRENT.get();
        CURRENT.set(organizationId);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** An open organization; closing it restores the previous one. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
