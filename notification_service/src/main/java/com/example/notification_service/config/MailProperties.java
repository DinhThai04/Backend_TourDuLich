package com.example.notification_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("app.mail")
public class MailProperties {
    private boolean enabled = false;
    private boolean testMode = true;
    private String testRecipient = "no-reply@sgu-tour.example";
    private String from = "no-reply@sgu-tour.example";
    private int batchSize = 10;
    private int maxAttempts = 3;
    private Duration lease = Duration.ofMinutes(2);

    public void validate() {
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 10
                || lease.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("Invalid mail batch, retry or lease configuration");
        }
        MailAddress.validate(from);
        if (testMode) MailAddress.validate(testRecipient);
    }
}
