package com.example.booking_service.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationMailClientTest {
    @Test
    void sendsBookingMailToNotificationContextWithInternalAuthentication() throws Exception {
        var authHeader = new AtomicReference<String>();
        var payload = new AtomicReference<String>();
        String secret = "notification-test-key";
        String mail = "{\"eventKey\":\"booking:18:created\"}";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notification/internal/notifications/mail", exchange -> {
            try (exchange) {
                authHeader.set(exchange.getRequestHeaders().getFirst("X-Internal-Notification-Key"));
                payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(202, -1);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/notification";
            new NotificationMailClient(base, secret, Duration.ofSeconds(2), Duration.ofSeconds(2)).sendBookingMail(mail);
            assertEquals(secret, authHeader.get());
            assertEquals(mail, payload.get());
        } finally {
            server.stop(0);
        }
    }
}
