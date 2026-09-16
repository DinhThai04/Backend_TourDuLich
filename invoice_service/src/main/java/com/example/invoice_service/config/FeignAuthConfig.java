package com.example.invoice_service.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

public class FeignAuthConfig {
    @Bean
    public RequestInterceptor authenticationRequestInterceptor() {
        return new AuthenticationRequestInterceptor();
    }
}
