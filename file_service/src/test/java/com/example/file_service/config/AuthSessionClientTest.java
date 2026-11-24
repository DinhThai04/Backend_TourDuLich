package com.example.file_service.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class AuthSessionClientTest {
    private HttpServer server;
    private AtomicReference<Integer> status;
    private AtomicReference<String> authorization;
    private AtomicReference<String> internalKey;
    private AtomicReference<String> path;
    private AuthSessionClient client;

    @BeforeEach
    void startServer() throws IOException {
        status = new AtomicReference<>(204);
        authorization = new AtomicReference<>();
        internalKey = new AtomicReference<>();
        path = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/internal/auth/sessions/validate", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            internalKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Auth-Key"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        client = new AuthSessionClient("http://127.0.0.1:" + server.getAddress().getPort() + "/auth",
                "test-internal-key", Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void forwardsBearerAndInternalCredentialToAuthBoundary() {
        client.requireActiveSession(jwt("signed-user-token"));

        assertTrue("/auth/internal/auth/sessions/validate".equals(path.get()));
        assertTrue("Bearer signed-user-token".equals(authorization.get()));
        assertTrue("test-internal-key".equals(internalKey.get()));
    }

    @Test
    void rejectsRevokedOrInvalidSessions() {
        status.set(401);
        assertThrows(BadJwtException.class, () -> client.requireActiveSession(jwt("revoked-token")));
    }

    @Test
    void failsClosedWhenAuthIsUnavailable() {
        status.set(503);
        assertThrows(JwtException.class, () -> client.requireActiveSession(jwt("signed-user-token")));
    }

    @Test
    void rejectsMissingInternalCredentialBeforeMakingRequest() {
        var unconfigured = new AuthSessionClient("http://127.0.0.1:" + server.getAddress().getPort() + "/auth",
                " ", Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThrows(JwtException.class, () -> unconfigured.requireActiveSession(jwt("signed-user-token")));
        assertTrue(path.get() == null);
    }

    private static Jwt jwt(String token) {
        return Jwt.withTokenValue(token).header("alg", "HS256").subject("42").build();
    }
}
