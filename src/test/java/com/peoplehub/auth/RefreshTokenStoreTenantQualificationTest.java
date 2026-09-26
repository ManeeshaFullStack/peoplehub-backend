package com.peoplehub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.common.database.TenantContext;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestIdentities;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * After a public flow resolves its organization (V24), its own lookup is still qualified by that
 * organization (b2-8 C3, D22): the refresh-token lookups by hash answer nothing for any other
 * organization, even for a real token.
 */
@IntegrationTest
class RefreshTokenStoreTenantQualificationTest {

    @Autowired private RefreshTokenStore store;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired @PrivilegedFixture private JdbcTemplate fixture;

    @Test
    void theHashLookupsAnswerOnlyInsideTheTokensOrganization() {
        TestIdentities.Organization org = TestIdentities.activeOrganization(fixture);
        TestIdentities.Organization other = TestIdentities.activeOrganization(fixture);
        TestIdentities.Employee employee =
                TestIdentities.activeEmployee(fixture, org, "EMPLOYEE", null);
        UUID family = TestIdentities.activeSession(fixture, employee, Instant.now());
        String hash =
                fixture.queryForObject(
                        "SELECT token_hash FROM refresh_token WHERE family_id = ?",
                        String.class,
                        family);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // Bound to the token's own organization (V25): the foreign-organization lookups are
        // filtered by the application's own predicate, with row-level security behind it.
        Optional<RefreshTokenStore.Owner> ownerInside;
        Optional<RefreshTokenStore.Owner> ownerElsewhere;
        Optional<RefreshTokenStore.StoredRefreshToken> tokenInside;
        Optional<RefreshTokenStore.StoredRefreshToken> tokenElsewhere;
        try (TenantContext.Scope tenant = TenantContext.open(org.id())) {
            ownerInside = tx.execute(status -> store.owner(org.id(), hash));
            ownerElsewhere = tx.execute(status -> store.owner(other.id(), hash));
            tokenInside = tx.execute(status -> store.findForUpdate(org.id(), hash));
            tokenElsewhere = tx.execute(status -> store.findForUpdate(other.id(), hash));
        }

        assertThat(ownerInside).isPresent();
        assertThat(ownerElsewhere).isEmpty();
        assertThat(tokenInside).isPresent();
        assertThat(tokenElsewhere).isEmpty();
    }
}
