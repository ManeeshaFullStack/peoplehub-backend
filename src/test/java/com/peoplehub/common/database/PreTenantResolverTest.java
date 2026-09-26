package com.peoplehub.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link PreTenantResolver} (b2-8 C3; O1, O3, O5): a found organization binds the calling
 * transaction, an unknown one binds nothing, it refuses to bind outside a transaction, and it never
 * touches {@link TenantContext}.
 */
@IntegrationTest
class PreTenantResolverTest {

    private static final String READ_SETTING =
            "SELECT current_setting('" + TenantBinding.SETTING + "', true)";

    @Autowired private PreTenantResolver resolver;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired @PrivilegedFixture private JdbcTemplate fixture;

    @Test
    void aFoundOrganizationBindsTheCallingTransaction() {
        TestIdentities.Organization org = TestIdentities.activeOrganization(fixture);

        String[] seen =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    Optional<UUID> found = resolver.bindByLoginKey(org.loginKey());
                                    return new String[] {
                                        found.map(UUID::toString).orElse(null), setting()
                                    };
                                });

        assertThat(seen[0]).isEqualTo(org.id().toString());
        assertThat(seen[1]).isEqualTo(org.id().toString());
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void anUnknownOrganizationBindsNothing() {
        String[] seen =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    Optional<UUID> found =
                                            resolver.bindByRefreshToken(
                                                    "unknown-" + UUID.randomUUID());
                                    return new String[] {
                                        String.valueOf(found.isPresent()), setting()
                                    };
                                });

        assertThat(seen[0]).isEqualTo("false");
        assertThat(seen[1]).isIn(null, "");
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void itRefusesToBindOutsideATransaction() {
        TestIdentities.Organization org = TestIdentities.activeOrganization(fixture);

        assertThatThrownBy(() -> resolver.bindByLoginKey(org.loginKey()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a transaction");
        assertThatThrownBy(() -> resolver.bindCreated(org.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void theBindingEndsWithTheTransaction() {
        TestIdentities.Organization org = TestIdentities.activeOrganization(fixture);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> resolver.bindByLoginKey(org.loginKey()));

        for (int i = 0; i < 25; i++) {
            String setting = tx.execute(status -> setting());
            assertThat(setting).isIn(null, "");
        }
    }

    private String setting() {
        return jdbc.sql(READ_SETTING).query(String.class).optional().orElse(null);
    }
}
