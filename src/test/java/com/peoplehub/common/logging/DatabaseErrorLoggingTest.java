package com.peoplehub.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hibernate logs the database's own error text as a log <em>message</em> when a statement fails,
 * and for a unique violation that text includes the offending value. This makes a real Postgres
 * reject a duplicate and asserts the value does not reach the log.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class DatabaseErrorLoggingTest {

    private static final String SECRET = "jane.doe@example.com";

    @PersistenceContext private EntityManager em;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void aRealUniqueViolationDoesNotLeakTheKeyIntoTheLog(CapturedOutput output) {
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactionManager)
                                        .executeWithoutResult(
                                                status -> {
                                                    em.createNativeQuery(
                                                                    "create temporary table"
                                                                            + " pii_probe (email text"
                                                                            + " unique)")
                                                            .executeUpdate();
                                                    String insert =
                                                            "insert into pii_probe values ('"
                                                                    + SECRET
                                                                    + "')";
                                                    em.createNativeQuery(insert).executeUpdate();
                                                    em.createNativeQuery(insert).executeUpdate();
                                                }))
                .hasMessageContaining("duplicate key");

        // The exception itself still carries the text for whoever handles it in code; what must
        // not happen is that it is written to the log.
        assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain("Key (email)");
    }
}
