package com.example.auth_service.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TourFeignConfiguration {

    @Bean
    public RequestInterceptor tourInternalRequestInterceptor(
            @Value("${TOUR_INTERNAL_KEY:}") String internalKey) {
        return template -> {
            if (internalKey != null && !internalKey.isBlank()) {
                template.header("X-Internal-Tour-Key", internalKey);
            }
        };
    }
}