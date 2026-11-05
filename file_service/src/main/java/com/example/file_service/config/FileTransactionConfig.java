package com.example.file_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class FileTransactionConfig {

    /**
     * Metadata edits run in a short explicit transaction while Cloudinary calls stay outside it.
     * READ_COMMITTED keeps the locked re-checks reading the latest committed gallery state.
     */
    @Bean
    TransactionTemplate fileTransactionTemplate(PlatformTransactionManager transactionManager) {
        var template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setName("file-metadata");
        return template;
    }
}
