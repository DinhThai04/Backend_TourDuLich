package com.example.booking_service.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@Getter @Setter
@ConfigurationProperties("app.mail")
public class MailProperties {
    private boolean enabled = false;
    private boolean testMode = true;
    private String testRecipient = "no-reply@sgu-tour.example";
    private String from = "no-reply@sgu-tour.example";
    private String brand = "SGU Tour";
    private String frontendBaseUrl = "http://localhost:5173";
    private int batchSize = 10;
    private int maxAttempts = 3;
    private Duration lease = Duration.ofMinutes(2);

    public void validate() {
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 10
                || lease.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("Invalid mail batch, retry or lease configuration");
        }
        MailOutboxService.validateAddress(from);
        if (testMode) MailOutboxService.validateAddress(testRecipient);
        var uri = java.net.URI.create(frontendBaseUrl);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Mail frontend URL must be an http(s) base URL");
        }
    }
}
