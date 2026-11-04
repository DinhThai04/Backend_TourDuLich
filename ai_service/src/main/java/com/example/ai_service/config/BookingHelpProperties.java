package com.example.ai_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Read-only booking-help lane. Its own permits keep chat turns and lookups from starving each other. */
@ConfigurationProperties("app.ai.booking-help")
public record BookingHelpProperties(@DefaultValue("2") int maxConcurrent) {
    public BookingHelpProperties {
        if(maxConcurrent<1 || maxConcurrent>8)
            throw new IllegalStateException("Booking-help concurrency outside supported local bounds.");
    }
    public static BookingHelpProperties defaults() { return new BookingHelpProperties(2); }
}
