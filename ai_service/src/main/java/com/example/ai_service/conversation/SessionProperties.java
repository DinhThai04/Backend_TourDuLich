package com.example.ai_service.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("app.ai.session")
public record SessionProperties(Duration ttl, int maxSessions, int historyTurns, int dedupeEntries) {
    public SessionProperties {
        if(ttl==null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(2))>0
                || maxSessions<1 || maxSessions>100 || historyTurns<1 || historyTurns>10 || dedupeEntries<1 || dedupeEntries>20)
            throw new IllegalArgumentException("Session settings exceed supported bounds.");
    }
}
