package com.example.tour_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.tour.internal")
@Data
public class TourInternalSecurityProperties {
    private String internalKey;
}