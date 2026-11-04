package com.example.ai_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** Deliberately not a record: generated toString must never disclose apiKey. */
@ConfigurationProperties("app.ai")
public class AiProperties {
    private boolean enabled;
    private boolean probe;
    private String provider = "groq";
    private String baseUrl = "https://api.groq.com/openai";
    private String model = "openai/gpt-oss-120b";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(30);
    private int maxCompletionTokens = 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isProbe() { return probe; }
    public void setProbe(boolean value) { probe = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
    public int getMaxCompletionTokens() { return maxCompletionTokens; }
    public void setMaxCompletionTokens(int value) { maxCompletionTokens = value; }

    public void validate() {
        if (!"groq".equals(provider) || !"https://api.groq.com/openai".equals(baseUrl))
            throw new IllegalStateException("AI provider/base URL is not approved for this local phase.");
        if (!"openai/gpt-oss-120b".equals(model))
            throw new IllegalStateException("AI model is not approved; review a different model before changing it.");
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || connectTimeout.compareTo(Duration.ofSeconds(10)) > 0
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                || readTimeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalStateException("AI timeouts are outside supported bounds.");
        if (maxCompletionTokens < 128 || maxCompletionTokens > 2048)
            throw new IllegalStateException("AI completion token limit must be 128..2048.");
        if (enabled && (apiKey == null || apiKey.isBlank() || apiKey.length() > 512
                || apiKey.chars().anyMatch(Character::isWhitespace)))
            throw new IllegalStateException("AI is enabled but GROQ_API_KEY is missing or invalid. Enter it locally.");
    }
}
