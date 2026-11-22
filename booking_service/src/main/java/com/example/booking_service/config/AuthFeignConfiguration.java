package com.example.booking_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthFeignConfiguration {

    @Value("${AUTH_INTERNAL_KEY:}")
    private String authInternalKey;

    @Bean
    public RequestInterceptor authInternalAuthInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                template.header("X-Internal-Auth-Key", authInternalKey);
            }
        };
    }
}