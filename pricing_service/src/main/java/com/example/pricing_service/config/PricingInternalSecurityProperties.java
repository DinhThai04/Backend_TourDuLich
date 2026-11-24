package com.example.pricing_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.pricing.internal")
@Data
public class PricingInternalSecurityProperties {
    private String internalKey;
}