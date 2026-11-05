package com.example.api_gateway.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Remembers recently accepted tokens for a few seconds so a page that fires several API
 * calls does not send one introspect round-trip per call.
 *
 * Only successful results are cached, and the token itself is never stored: the key is a
 * SHA-256 hash. A revoked token therefore keeps working for at most the configured TTL,
 * which is why the default is short (10 seconds).
 */
@Component
public class IntrospectionCache {

    private static final int MAX_ENTRIES = 20_000;

    private final ConcurrentHashMap<String, Instant> acceptedUntil = new ConcurrentHashMap<>();
    private final Duration ttl;

    public IntrospectionCache(@Value("${app.auth.introspect-cache-seconds:10}") long ttlSeconds) {
        this.ttl = Duration.ofSeconds(Math.max(0, ttlSeconds));
    }

    public boolean isAccepted(String token) {
        if (ttl.isZero() || token == null) return false;
        String key = keyOf(token);
        Instant expiresAt = acceptedUntil.get(key);
        if (expiresAt == null) return false;
        if (expiresAt.isAfter(Instant.now())) return true;
        acceptedUntil.remove(key, expiresAt);
        return false;
    }

    public void remember(String token) {
        if (ttl.isZero() || token == null) return;
        Instant now = Instant.now();
        if (acceptedUntil.size() >= MAX_ENTRIES) {
            // Expired entries are the bulk of a full map (the TTL is seconds), so drop those first
            // instead of clearing every still-valid token and sending all traffic back to auth.
            acceptedUntil.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
            if (acceptedUntil.size() >= MAX_ENTRIES) acceptedUntil.clear();
        }
        acceptedUntil.put(keyOf(token), now.plus(ttl));
    }

    private static String keyOf(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }
}
