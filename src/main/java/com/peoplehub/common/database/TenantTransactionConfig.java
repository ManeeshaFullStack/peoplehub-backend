package com.peoplehub.common.database;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;

/**
 * Registers {@link TenantTransactionManager} as the application's transaction manager (b2-8, owner
 * decision O2). It is built exactly as Spring Boot's JPA auto-configuration builds its own {@code
 * JpaTransactionManager} (which backs off when this bean exists): the entity manager factory is
 * found from the bean factory, and Boot's transaction-manager customizers are applied.
 */
@Configuration(proxyBeanMethods = false)
public class TenantTransactionConfig {

    @Bean
    JpaTransactionManager transactionManager(
            TenantBinding tenantBinding,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        TenantTransactionManager transactionManager = new TenantTransactionManager(tenantBinding);
        customizers.ifAvailable(c -> c.customize(transactionManager));
        return transactionManager;
    }
}
