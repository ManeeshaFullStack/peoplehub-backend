package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.TestMfaKeysEnvironment;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The MFA keys are loaded eagerly (b2-7, B2-7/7): a context with a valid key starts and can
 * encrypt; a missing or invalid key stops it, and the failure never contains the key.
 */
class MfaConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(MfaConfig.class);

    @Test
    void aValidKeyStartsTheContextAndTheCipherWorks() {
        runner.withPropertyValues(
                        "peoplehub.mfa.encryption-key=" + TestMfaKeysEnvironment.generate(),
                        "peoplehub.mfa.encryption-key-id=k1")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            MfaSecretCipher cipher = context.getBean(MfaSecretCipher.class);
                            UUID org = UUID.randomUUID();
                            UUID employee = UUID.randomUUID();
                            byte[] secret = Totp.newSecret();
                            assertThat(
                                            cipher.decrypt(
                                                    cipher.encrypt(secret, org, employee),
                                                    org,
                                                    employee))
                                    .isEqualTo(secret);
                        });
    }

    @Test
    void theContextRefusesToStartWithoutAKey() {
        runner.withPropertyValues("peoplehub.mfa.encryption-key-id=k1")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessage("PEOPLEHUB_MFA_ENCRYPTION_KEY is required"));
        runner.withPropertyValues(
                        "peoplehub.mfa.encryption-key=" + TestMfaKeysEnvironment.generate())
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageStartingWith(
                                                "PEOPLEHUB_MFA_ENCRYPTION_KEY_ID needs a key id"));
    }

    @Test
    void theContextRefusesToStartWithAnInvalidKeyAndNeverEchoesIt() {
        String tooShort = TestMfaKeysEnvironment.generate().substring(0, 24);

        runner.withPropertyValues(
                        "peoplehub.mfa.encryption-key=" + tooShort,
                        "peoplehub.mfa.encryption-key-id=k1")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            Throwable failure = context.getStartupFailure();
                            for (Throwable t = failure; t != null; t = t.getCause()) {
                                assertThat(String.valueOf(t.getMessage())).doesNotContain(tooShort);
                            }
                            assertThat(failure)
                                    .rootCause()
                                    .hasMessageStartingWith("PEOPLEHUB_MFA_ENCRYPTION_KEY must");
                        });
    }
}
