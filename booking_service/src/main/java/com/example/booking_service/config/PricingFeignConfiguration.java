package com.example.booking_service.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PricingFeignConfiguration {

    @Bean
    public RequestInterceptor pricingInternalRequestInterceptor(
            @Value("${PRICING_INTERNAL_KEY:}") String internalKey) {
        return template -> {
            if (internalKey != null && !internalKey.isBlank()) {
                template.header("X-Internal-Pricing-Key", internalKey);
            }
        };
    }
}