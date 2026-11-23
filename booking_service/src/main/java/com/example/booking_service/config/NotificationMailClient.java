package com.example.booking_service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls Notification's mail-requested boundary instead of Booking owning mail delivery. */
@Component
public class NotificationMailClient {
    private final RestClient client;
    private final String internalKey;

    public NotificationMailClient(
            @Value("${app.notification.base-url:http://localhost:8082/notification}") String baseUrl,
            @Value("${NOTIFICATION_INTERNAL_KEY:}") String internalKey,
            @Value("${app.notification.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.notification.read-timeout:3s}") Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalKey = internalKey;
    }

    public void sendBookingMail(String payloadJson) {
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalStateException("Notification internal API key is not configured");
        }
        try {
            client.post()
                    .uri("/internal/notifications/mail")
                    .header("X-Internal-Notification-Key", internalKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payloadJson)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            (request, response) -> { throw new RuntimeException("Notification rejected mail event"); })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, response) -> { throw new RuntimeException("Notification unavailable"); })
                    .toBodilessEntity();
        } catch (RestClientException failure) {
            throw new RuntimeException("Notification mail send failed", failure);
        }
    }
}