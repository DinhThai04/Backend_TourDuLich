package com.example.booking_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.booking.internal")
@Data
public class BookingInternalSecurityProperties {
    private String internalKey;
    private String ticketMailKey;
}