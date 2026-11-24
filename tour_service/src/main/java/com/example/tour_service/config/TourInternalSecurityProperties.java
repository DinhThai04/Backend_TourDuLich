package com.example.tour_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.tour.internal")
@Data
public class TourInternalSecurityProperties {
    private String internalKey;
}
