package com.example.invoice_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.invoice.internal")
@Data
public class InvoiceInternalSecurityProperties {
    private String internalKey;
}