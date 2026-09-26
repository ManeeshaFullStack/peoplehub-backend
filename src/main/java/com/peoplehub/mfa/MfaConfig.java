package com.peoplehub.mfa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MFA secret encryption (b2-7, B2-7/7). The keys are loaded eagerly, so the application refuses to
 * start without a valid one even while every organization has MFA {@code DISABLED} (fail fast, the
 * same as the access-token key, B2-3/2).
 */
@Configuration(proxyBeanMethods = false)
public class MfaConfig {

    @Bean
    MfaEncryptionKeys mfaEncryptionKeys(
            @Value("${peoplehub.mfa.encryption-key:#{null}}") String key,
            @Value("${peoplehub.mfa.encryption-key-id:#{null}}") String keyId,
            @Value("${peoplehub.mfa.previous-encryption-keys:}") String previousKeys) {
        return MfaEncryptionKeys.from(key, keyId, previousKeys);
    }

    @Bean
    MfaSecretCipher mfaSecretCipher(MfaEncryptionKeys keys) {
        return new MfaSecretCipher(keys);
    }
}
