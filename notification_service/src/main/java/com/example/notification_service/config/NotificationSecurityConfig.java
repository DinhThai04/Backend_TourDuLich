package com.example.notification_service.config;

import com.example.notification_service.security.InternalNotificationAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationSecurityConfig {

    @Bean
    public FilterRegistrationBean<InternalNotificationAuthenticationFilter> internalNotificationFilter(
            @Value("${app.notification.internal-key:}") String key) {
        FilterRegistrationBean<InternalNotificationAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InternalNotificationAuthenticationFilter(key));
        registration.addUrlPatterns("/internal/notifications/*");
        registration.addUrlPatterns("/internal/notification/dlq/*");
        registration.setName("internalNotificationAuthenticationFilter");
        registration.setOrder(1);
        return registration;
    }
}
