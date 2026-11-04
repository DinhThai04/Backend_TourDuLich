package com.example.ai_service.model;

/** No upstream exception/body is retained: it can contain credentials or submitted text. */
public final class ModelFailure extends RuntimeException {
    public enum Type {
        AI_PROVIDER_NOT_CONFIGURED, AI_PROVIDER_UNAVAILABLE, AI_RATE_LIMITED, AI_TIMEOUT, AI_INVALID_OUTPUT,
        AI_QUOTA_EXHAUSTED, AI_INPUT_TOO_LARGE, AI_CAPACITY_REACHED
    }
    private final Type type;
    private final Integer retryAfterSeconds;
    public ModelFailure(Type type) { this(type, null); }
    public ModelFailure(Type type, Integer retryAfterSeconds) {
        super(type.name());
        this.type = type;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public Type type() { return type; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
    public boolean retryable() {
        return type == Type.AI_RATE_LIMITED || type == Type.AI_TIMEOUT || type == Type.AI_CAPACITY_REACHED;
    }
    public int httpStatus() {
        return switch (type) {
            case AI_RATE_LIMITED -> 429;
            case AI_TIMEOUT -> 504;
            case AI_INVALID_OUTPUT -> 502;
            case AI_INPUT_TOO_LARGE -> 400;
            default -> 503;
        };
    }
}
