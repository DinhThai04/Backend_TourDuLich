package com.example.notification_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class InternalNotificationAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Notification-Key";

    private final String key;

    public InternalNotificationAuthenticationFilter(String key) {
        this.key = key;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (key == null || key.length() < 32) {
            reject(response, 503, "Internal notification API is not configured");
            return;
        }
        String supplied = request.getHeader(HEADER);
        if (supplied == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            reject(response, 401, "Invalid internal notification credential");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":1401,\"message\":\"" + message + "\"}");
    }
}
