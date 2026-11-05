package com.example.ai_service.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/**
 * Local spend and deadline budgets. {@code promptUtf8Bytes} is the stricter per-model-call guard derived
 * from the minute token budget; the documented request cap stays 16000 code points
 * ({@code ChatModels.MAX_MESSAGE_POINTS}) enforced by {@code AiInputPolicy} and {@code ChatBodyLimit}.
 */
@ConfigurationProperties("app.ai.guard")
public record GuardProperties(@DefaultValue("8") int requestsPerMinute,
                              @DefaultValue("80") int requestsPerDay,
                              @DefaultValue("7000") int tokensPerMinute,
                              @DefaultValue("150000") int tokensPerDay,
                              @DefaultValue("11000") int promptUtf8Bytes,
                              @DefaultValue("38s") Duration turnTimeout) {
    public GuardProperties {
        if(requestsPerMinute<1 || requestsPerMinute>20 || requestsPerDay<1 || requestsPerDay>500
                || tokensPerMinute<128 || tokensPerMinute>8000 || tokensPerDay<128 || tokensPerDay>200000
                || promptUtf8Bytes<256 || promptUtf8Bytes>11000 || turnTimeout==null
                || turnTimeout.compareTo(Duration.ofMillis(50))<0 || turnTimeout.compareTo(Duration.ofSeconds(40))>0)
            throw new IllegalStateException("AI guard settings outside supported local bounds.");
    }
    public static GuardProperties defaults() { return new GuardProperties(8,80,7000,150000,11000,Duration.ofSeconds(38)); }
}
