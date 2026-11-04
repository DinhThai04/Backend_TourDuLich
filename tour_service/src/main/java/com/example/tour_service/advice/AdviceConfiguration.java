package com.example.tour_service.advice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableConfigurationProperties(AdviceConfiguration.Limits.class)
public class AdviceConfiguration {
    @Bean("adviceClock") Clock adviceClock() { return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")); }

    @ConfigurationProperties("app.advice")
    public record Limits(Integer maxCandidateTours, Integer maxFutureDepartures, Integer maxPartySize) {
        public Limits {
            maxCandidateTours = bounded(maxCandidateTours, 500, 1, 5000);
            maxFutureDepartures = bounded(maxFutureDepartures, 10000, 1, 50000);
            maxPartySize = bounded(maxPartySize, 100, 1, 1000);
        }
        private static int bounded(Integer value, int fallback, int min, int max) {
            int chosen = value == null ? fallback : value;
            if (chosen < min || chosen > max) throw new IllegalArgumentException("Advice limit outside supported range");
            return chosen;
        }
    }
}
