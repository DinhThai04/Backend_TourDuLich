package com.example.auth_service.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InvoiceFeignConfiguration {

    @Value("${INVOICE_INTERNAL_KEY:}")
    private String invoiceInternalKey;

    @Bean
    public RequestInterceptor invoiceInternalRequestInterceptor() {
        return template -> {
            if (invoiceInternalKey == null || invoiceInternalKey.isBlank()) {
                throw new IllegalStateException("INVOICE_INTERNAL_KEY is required but not configured");
            }
            template.header("X-Internal-Invoice-Key", invoiceInternalKey);
        };
    }
}
