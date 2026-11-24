package com.example.reporting_service.config;

import com.example.reporting_service.security.InternalReportingAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReportingSecurityConfig {

    @Bean
    public FilterRegistrationBean<InternalReportingAuthenticationFilter> internalReportingFilter(
            @Value("${app.reporting.internal-key:}") String key) {
        FilterRegistrationBean<InternalReportingAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InternalReportingAuthenticationFilter(key));
        registration.addUrlPatterns("/internal/reports/*");
        registration.setName("internalReportingAuthenticationFilter");
        registration.setOrder(1);
        return registration;
    }
}
