package com.example.file_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.file.internal")
@Data
public class FileInternalSecurityProperties {
    private String internalKey;
}