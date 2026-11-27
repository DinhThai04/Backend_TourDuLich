package com.example.ai_service.config;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Loopback hosts for native runs, plus the compose service names used inside the Docker network. */
public final class LocalServiceUrl {
    private static final Set<String> LOCAL_HOSTS = Set.of(
            "127.0.0.1", "localhost", "api-gateway", "booking-service");

    private LocalServiceUrl() {}

    public static void requireLocal(String baseUrl, String expectedPath, String message) {
        requireLocal(baseUrl, expectedPath, message, false);
    }

    public static void requireCatalog(String baseUrl) {
        requireLocal(baseUrl, "/api/v1/tour", "Invalid local catalog URL.", true);
    }

    private static void requireLocal(String baseUrl, String expectedPath, String message, boolean allowDirectTour) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(message, invalid);
        }
        boolean validGatewayOrBackend = expectedPath.equals(uri.getPath()) && uri.getHost() != null
                && LOCAL_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
        boolean validDirectTour = allowDirectTour && "tour-service".equals(uri.getHost())
                && uri.getPort() == 8086 && "/tour".equals(uri.getPath());
        if (!"http".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(validGatewayOrBackend || validDirectTour))
            throw new IllegalStateException(message);
    }
}
