package com.example.ai_service.web;

import com.example.ai_service.config.AiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final AiProperties properties;
    public HealthController(AiProperties properties) { this.properties = properties; }
    public record HealthEnvelope(int code, Health result) {}
    public record Health(String service, String status, String provider, String model, boolean configured,
                         String providerConnection, String phase) {}

    @GetMapping("/health")
    public HealthEnvelope health() {
        return new HealthEnvelope(1000, new Health("ai_service", properties.isEnabled() ? "UP" : "DISABLED",
                properties.getProvider(), properties.getModel(), properties.isEnabled(), "NOT_CHECKED", "H"));
    }
}
