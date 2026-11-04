package com.example.auth_service.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.SocketIOServer;


@Configuration
public class SocketIOConfig {

    /**
     * Comma-separated browser origins allowed to complete the Socket.IO handshake. nginx serves
     * the frontend same-origin, so the production host must be appended to this list.
     */
    @Bean
    public SocketIOServer socketIOServer(
            @org.springframework.beans.factory.annotation.Value("${app.socket.port:8099}") int port,
            @org.springframework.beans.factory.annotation.Value("${app.socket.allowed-origins:http://localhost:5173,http://localhost,http://127.0.0.1}") String allowedOrigins) {
        Set<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        com.corundumstudio.socketio.Configuration configuration = new com.corundumstudio.socketio.Configuration();
        configuration.setPort(port);
        // netty-socketio writes one Access-Control-Allow-Origin value; with several configured
        // origins let it echo the request origin, and reject anything outside the list below.
        configuration.setOrigin(origins.size() == 1 ? origins.iterator().next() : null);
        configuration.setAuthorizationListener(data -> {
            String origin = data.getHttpHeaders().get("Origin");
            // Non-browser clients omit Origin; browser handshakes must match the allowlist.
            return new AuthorizationResult(origin == null || origins.contains(origin));
        });
        return new SocketIOServer(configuration);
    }
}
