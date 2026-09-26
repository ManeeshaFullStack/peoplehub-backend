package com.peoplehub.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenants of parallel threads, and of a new transaction started inside another, never mix (b2-8,
 * O1): every transaction sees exactly the tenant its own thread opened when it began.
 */
@IntegrationTest
class TenantTransactionConcurrencyTest {

    private static final String READ_SETTING =
            "SELECT current_setting('" + TenantBinding.SETTING + "', true)";

    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void parallelThreadsEachSeeOnlyTheirOwnTenant() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<String>>> results = new ArrayList<>();
            List<UUID> tenants = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                UUID tenant = UUID.randomUUID();
                tenants.add(tenant);
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    List<String> seen = new ArrayList<>();
                                    TransactionTemplate transaction =
                                            new TransactionTemplate(transactionManager);
                                    for (int i = 0; i < 25; i++) {
                                        try (TenantContext.Scope scope =
                                                TenantContext.open(tenant)) {
                                            seen.add(transaction.execute(status -> setting()));
                                        }
                                    }
                                    return seen;
                                }));
            }
            start.countDown();
            for (int t = 0; t < threads; t++) {
                assertThat(results.get(t).get(60, TimeUnit.SECONDS))
                        .hasSize(25)
                        .containsOnly(tenants.get(t).toString());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aNewTransactionInsideAnotherAppliesItsOwnTenantAndLeavesTheOuterOneAlone() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<String> seen = new ArrayList<>();
        try (TenantContext.Scope scope = TenantContext.open(outer)) {
            transaction.executeWithoutResult(
                    status -> {
                        seen.add(setting());
                        try (TenantContext.Scope nested = TenantContext.open(inner)) {
                            seen.add(requiresNew.execute(innerStatus -> setting()));
                        }
                        seen.add(setting());
                    });
        }

        assertThat(seen).containsExactly(outer.toString(), inner.toString(), outer.toString());
    }

    private String setting() {
        return jdbc.sql(READ_SETTING).query(String.class).optional().orElse(null);
    }
}
