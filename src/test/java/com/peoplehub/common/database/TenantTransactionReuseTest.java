package com.peoplehub.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tenant setting is transaction-local and never survives into the next use of a pooled
 * connection (b2-8, owner decisions O1, O2). The pool here has exactly <b>one</b> connection, and
 * every test checks with {@code pg_backend_pid()} that consecutive transactions really ran on the
 * same physical PostgreSQL session, so "the next transaction did not see it" is proof, not luck.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=1")
class TenantTransactionReuseTest {

    private static final String READ_SETTING =
            "SELECT current_setting('" + TenantBinding.SETTING + "', true)";

    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TenantBinding tenantBinding;
    @Autowired private TenantTransactions tenantTransactions;

    private TransactionTemplate transaction;

    @BeforeEach
    void template() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void theApplicationUsesTheTenantAwareTransactionManager() {
        assertThat(transactionManager).isInstanceOf(TenantTransactionManager.class);
    }

    @Test
    void aTransactionCarriesTheOpenTenant() {
        UUID a = UUID.randomUUID();

        String inside;
        try (TenantContext.Scope scope = TenantContext.open(a)) {
            inside = transaction.execute(status -> setting());
        }

        assertThat(inside).isEqualTo(a.toString());
    }

    @Test
    void theSettingEndsWithACommitOnTheSamePooledConnection() {
        UUID a = UUID.randomUUID();
        Session during;
        try (TenantContext.Scope scope = TenantContext.open(a)) {
            during = transaction.execute(status -> session());
        }

        Session after = session(); // outside any transaction, same (only) pooled connection

        assertThat(during.setting()).isEqualTo(a.toString());
        assertThat(after.pid()).isEqualTo(during.pid());
        assertThat(after.setting()).isNullOrEmpty();
    }

    @Test
    void theSettingEndsWithARollbackOnTheSamePooledConnection() {
        UUID a = UUID.randomUUID();
        int[] pid = new int[1];
        try (TenantContext.Scope scope = TenantContext.open(a)) {
            assertThatThrownBy(
                            () ->
                                    transaction.execute(
                                            status -> {
                                                pid[0] = session().pid();
                                                throw new IllegalStateException("rolled back");
                                            }))
                    .isInstanceOf(IllegalStateException.class);
        }

        Session after = session();

        assertThat(after.pid()).isEqualTo(pid[0]);
        assertThat(after.setting()).isNullOrEmpty();
    }

    @Test
    void aTransactionWithoutATenantHasNoSettingEvenRightAfterOneWithATenant() {
        UUID a = UUID.randomUUID();
        Session first;
        try (TenantContext.Scope scope = TenantContext.open(a)) {
            first = transaction.execute(status -> session());
        }

        Session second = transaction.execute(status -> session());

        assertThat(second.pid()).isEqualTo(first.pid());
        assertThat(second.setting()).isNullOrEmpty();
    }

    @Test
    void consecutiveTenantsOnOneConnectionNeverSeeEachOther() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Integer pid = null;
        for (int i = 0; i < 30; i++) {
            UUID expected =
                    switch (i % 3) {
                        case 0 -> a;
                        case 1 -> b;
                        default -> null;
                    };
            Session seen;
            if (expected == null) {
                seen = transaction.execute(status -> session());
            } else {
                try (TenantContext.Scope scope = TenantContext.open(expected)) {
                    seen = transaction.execute(status -> session());
                }
            }
            if (pid == null) {
                pid = seen.pid();
            }
            assertThat(seen.pid())
                    .as("iteration %d ran on the one pooled connection", i)
                    .isEqualTo(pid);
            if (expected == null) {
                assertThat(seen.setting()).as("iteration %d", i).isNullOrEmpty();
            } else {
                assertThat(seen.setting()).as("iteration %d", i).isEqualTo(expected.toString());
            }
        }
    }

    @Test
    void bindingInsideATransactionSetsItForThatTransactionOnly() {
        UUID a = UUID.randomUUID();

        Session during =
                transaction.execute(
                        status -> {
                            assertThat(setting()).isNullOrEmpty();
                            tenantBinding.bindCurrentTransaction(a);
                            return session();
                        });
        Session after = session();

        assertThat(during.setting()).isEqualTo(a.toString());
        assertThat(after.pid()).isEqualTo(during.pid());
        assertThat(after.setting()).isNullOrEmpty();
    }

    @Test
    void bindingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> tenantBinding.bindCurrentTransaction(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a transaction");
        assertThat(setting()).isNullOrEmpty();
    }

    @Test
    void theHelperRunsEachCallInItsOwnTenantTransaction() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        String readWrite = tenantTransactions.inTransaction(a, this::setting);
        String readOnly =
                tenantTransactions.inReadOnlyTransaction(
                        b,
                        () -> {
                            assertThat(
                                            jdbc.sql("SHOW transaction_read_only")
                                                    .query(String.class)
                                                    .single())
                                    .isEqualTo("on");
                            return setting();
                        });

        assertThat(readWrite).isEqualTo(a.toString());
        assertThat(readOnly).isEqualTo(b.toString());
        assertThat(TenantContext.current()).isEmpty();
        assertThat(setting()).isNullOrEmpty();
    }

    @Test
    void aJoiningTransactionKeepsTheTenantOfTheTransactionItJoins() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        String joined;
        try (TenantContext.Scope outer = TenantContext.open(a)) {
            joined =
                    transaction.execute(
                            status -> {
                                try (TenantContext.Scope inner = TenantContext.open(b)) {
                                    // REQUIRED joins the running transaction: nothing begins, so
                                    // nothing is re-applied.
                                    return transaction.execute(innerStatus -> setting());
                                }
                            });
        }

        assertThat(joined).isEqualTo(a.toString());
    }

    private String setting() {
        return jdbc.sql(READ_SETTING).query(String.class).optional().orElse(null);
    }

    private Session session() {
        return jdbc.sql(
                        "SELECT pg_backend_pid() AS pid, current_setting('"
                                + TenantBinding.SETTING
                                + "', true) AS setting")
                .query((rs, rowNum) -> new Session(rs.getInt("pid"), rs.getString("setting")))
                .single();
    }

    private record Session(int pid, String setting) {}
}
