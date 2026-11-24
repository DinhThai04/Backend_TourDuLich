package com.example.booking_service.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenRevocationCheckerTest {
    private static final String INTERNAL_KEY = "internal-auth-test-key";

    private HttpServer server;
    private AtomicInteger responseStatus;
    private AtomicReference<String> receivedAuthHeader;
    private AtomicReference<String> receivedInternalKey;
    private String baseUrl;
    private TokenRevocationChecker checker;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = new AtomicInteger(204);
        receivedAuthHeader = new AtomicReference<>();
        receivedInternalKey = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/internal/auth/sessions/validate", exchange -> {
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedInternalKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Auth-Key"));
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth";
        checker = new TokenRevocationChecker(
                new BookingAuthSessionClient(baseUrl, INTERNAL_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void acceptsActiveSessionValidatedByAuth() {
        responseStatus.set(204);
        assertDoesNotThrow(() -> checker.verify(token("raw-jwt-1")));
        assertEquals("Bearer raw-jwt-1", receivedAuthHeader.get());
        assertEquals(INTERNAL_KEY, receivedInternalKey.get());
    }

    @Test
    void rejectsRevokedOrLockedSessionOn4xx() {
        responseStatus.set(401);
        assertThrows(BadJwtException.class, () -> checker.verify(token("raw-jwt-2")));
    }

    @Test
    void failsClosedWhenAuthIsUnavailableOrReturns5xx() {
        responseStatus.set(503);
        assertThrows(JwtException.class, () -> checker.verify(token("raw-jwt-3")));

        server.stop(0);
        assertThrows(JwtException.class, () -> checker.verify(token("raw-jwt-4")));
    }

    @Test
    void failsClosedWhenInternalKeyIsMissing() {
        var unconfigured = new TokenRevocationChecker(
                new BookingAuthSessionClient(baseUrl, "", Duration.ofSeconds(2), Duration.ofSeconds(2)));
        assertThrows(JwtException.class, () -> unconfigured.verify(token("raw-jwt-5")));
    }

    private Jwt token(String value) {
        return Jwt.withTokenValue(value)
                .header("alg", "HS256")
                .subject("7")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("jti", "jti-1")
                .build();
    }
}
