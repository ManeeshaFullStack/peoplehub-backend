package com.peoplehub.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The in-memory tenant of a thread (b2-8, O1): scoped, nestable, per thread. */
class TenantContextTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @AfterEach
    void nothingLeftOpen() {
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void nothingIsOpenByDefault() {
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aScopeSetsTheTenantUntilItIsClosed() {
        try (TenantContext.Scope scope = TenantContext.open(A)) {
            assertThat(TenantContext.current()).contains(A);
        }
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void closingANestedScopeRestoresTheOuterTenant() {
        try (TenantContext.Scope outer = TenantContext.open(A)) {
            try (TenantContext.Scope inner = TenantContext.open(B)) {
                assertThat(TenantContext.current()).contains(B);
            }
            assertThat(TenantContext.current()).contains(A);
        }
    }

    @Test
    void theScopeIsRestoredWhenTheWorkThrows() {
        assertThatThrownBy(
                        () -> {
                            try (TenantContext.Scope scope = TenantContext.open(A)) {
                                throw new IllegalStateException("boom");
                            }
                        })
                .isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aNullTenantIsRefused() {
        assertThatThrownBy(() -> TenantContext.open(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void eachThreadHasItsOwnTenant() throws Exception {
        AtomicReference<Optional<UUID>> seenElsewhere = new AtomicReference<>();
        try (TenantContext.Scope scope = TenantContext.open(A)) {
            Thread other = new Thread(() -> seenElsewhere.set(TenantContext.current()));
            other.start();
            other.join();
        }
        assertThat(seenElsewhere.get()).isEmpty();
    }
}
