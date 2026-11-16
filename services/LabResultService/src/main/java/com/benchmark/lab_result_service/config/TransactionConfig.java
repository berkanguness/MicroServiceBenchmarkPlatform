package com.benchmark.lab_result_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read transaction of every service call (contract §5.5): a programmatic {@link TransactionTemplate}
 * instead of {@code @Transactional}, so that a failure at transaction begin surfaces inside the service method.
 */
@Configuration(proxyBeanMethods = false)
public class TransactionConfig {

    /** Bean name of the read-only template used by the read path. */
    public static final String READ_TRANSACTION_TEMPLATE = "readTransactionTemplate";

    @Bean(READ_TRANSACTION_TEMPLATE)
    TransactionTemplate readTransactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        template.setTimeout(TransactionDefinition.TIMEOUT_DEFAULT);
        return template;
    }
}
