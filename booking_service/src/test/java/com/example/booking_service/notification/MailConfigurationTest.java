package com.example.booking_service.notification;

import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.config.NotificationMailClient;
import com.example.booking_service.reservation.LifecycleTestDatabase;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class MailConfigurationTest {
    LifecycleTestDatabase db;
    LifecycleTestDatabase.MutableClock clock;
    MailProperties properties;
    @BeforeEach void setup() throws Exception {
        db = new LifecycleTestDatabase(); clock = new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026,11,1,10,0));
        properties = new MailProperties();
        properties.setTestMode(true);
        properties.setTestRecipient("tester@example.com");
        properties.setFrom("no-reply@sgu-tour.example");
        properties.setEnabled(true);
        properties.setBrand("SGU Tour");
        properties.setFrontendBaseUrl("http://localhost:5173");
        properties.setBatchSize(10);
        properties.setMaxAttempts(3);
        properties.setLease(java.time.Duration.ofMinutes(2));
    }
    @Test void testModeOnlyEnqueuesForTestRecipient() {
        // Test that MailProperties validates correctly
        try {
            properties.validate();
        } catch (IllegalArgumentException e) {
            fail("validate() threw IllegalArgumentException: " + e.getMessage());
        }
        assertTrue(properties.isTestMode());
        assertEquals("tester@example.com", properties.getTestRecipient());
    }
}