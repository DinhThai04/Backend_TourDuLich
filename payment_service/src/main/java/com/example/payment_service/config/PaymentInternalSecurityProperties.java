package com.example.payment_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.payment.internal")
@Data
public class PaymentInternalSecurityProperties {
    private String internalKey;
}