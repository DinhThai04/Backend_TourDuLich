package com.example.ai_service.config;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Loopback hosts for native runs, plus the compose service names used inside the Docker network. */
public final class LocalServiceUrl {
    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost", "api_gateway", "booking_service");

    private LocalServiceUrl() {}

    public static void requireLocal(String baseUrl, String expectedPath, String message) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(message, invalid);
        }
        if (!"http".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !expectedPath.equals(uri.getPath())
                || !LOCAL_HOSTS.contains(hostOf(uri)))
            throw new IllegalStateException(message);
    }

    /** Reads the authority directly: Java parses underscore names like api_gateway as registry-based, so getHost() is null. */
    private static String hostOf(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null || authority.indexOf('@') >= 0) return null;
        int port = authority.lastIndexOf(':');
        String host = port >= 0 ? authority.substring(0, port) : authority;
        return host.toLowerCase(Locale.ROOT);
    }
}
