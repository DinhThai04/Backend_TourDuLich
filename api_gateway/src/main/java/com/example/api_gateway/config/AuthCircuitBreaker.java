package com.example.api_gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal in-process breaker around auth introspection. When the auth service keeps failing,
 * short-circuit to 503 instead of queueing every request behind the full connect/response timeout.
 */
@Component
public class AuthCircuitBreaker {
    private final int failureThreshold;
    private final long openMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntil;

    public AuthCircuitBreaker(
            @Value("${app.auth.breaker-failure-threshold:5}") int failureThreshold,
            @Value("${app.auth.breaker-open-ms:10000}") long openMs) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMs = Math.max(1, openMs);
    }

    boolean allowRequest(long nowMs) {
        return nowMs >= openUntil;
    }

    boolean allowRequest() {
        return allowRequest(System.currentTimeMillis());
    }

    void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil = 0;
    }

    void recordFailure(long nowMs) {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntil = nowMs + openMs;
            // Start a fresh count: otherwise one failure after the window reopens the breaker forever.
            consecutiveFailures.set(0);
        }
    }

    void recordFailure() {
        recordFailure(System.currentTimeMillis());
    }
}
