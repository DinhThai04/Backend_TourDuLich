package com.example.auth_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth.internal")
@Data
public class AuthInternalSecurityProperties {
    private String internalKey;
}