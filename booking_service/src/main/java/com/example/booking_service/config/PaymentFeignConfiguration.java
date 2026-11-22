package com.example.booking_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentFeignConfiguration {

    @Value("${PAYMENT_INTERNAL_KEY:}")
    private String paymentInternalKey;

    @Bean
    public RequestInterceptor paymentInternalAuthInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                template.header("X-Internal-Payment-Key", paymentInternalKey);
            }
        };
    }
}