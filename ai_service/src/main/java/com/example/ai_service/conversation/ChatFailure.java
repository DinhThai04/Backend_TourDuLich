package com.example.ai_service.conversation;

public final class ChatFailure extends RuntimeException {
    private final int status;
    private final String type;
    private final Integer retryAfterSeconds;
    public ChatFailure(int status, String type) { this(status,type,null); }
    public ChatFailure(int status, String type, Integer retryAfterSeconds) {
        super(type); this.status=status; this.type=type; this.retryAfterSeconds=retryAfterSeconds;
    }
    public int status() { return status; }
    public String type() { return type; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
    public static ChatFailure invalid() { return new ChatFailure(400,"INVALID_REQUEST"); }
    public static ChatFailure expired() { return new ChatFailure(410,"CONVERSATION_EXPIRED"); }
    public static ChatFailure catalog() { return new ChatFailure(503,"CATALOG_UNAVAILABLE"); }
}
