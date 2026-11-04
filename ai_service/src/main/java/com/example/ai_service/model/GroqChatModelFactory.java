package com.example.ai_service.model;

import com.example.ai_service.config.AiProperties;
import com.example.ai_service.guard.BoundedHttp;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class GroqChatModelFactory {
    private static final String COMPLETIONS_PATH="/v1/chat/completions";
    private static final int MAX_RESPONSE_BYTES=131072;
    private static final String REASONING_EFFORT="low";
    private static final String INCLUDE_REASONING="include_reasoning";
    private static final boolean REASONING_ENABLED=false;
    private static final int SINGLE_ATTEMPT=1;
    private static final int MAX_RETRY_AFTER_SECONDS=86400;
    private static final String RETRY_AFTER_HEADER="Retry-After";
    private GroqChatModelFactory() {}
    public static OpenAiChatModel create(AiProperties properties) {
        var httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var requests = BoundedHttp.requests(httpClient,properties.getReadTimeout());
        var api = OpenAiApi.builder().baseUrl(properties.getBaseUrl()).apiKey(properties.getApiKey())
                .completionsPath(COMPLETIONS_PATH)
                .restClientBuilder(RestClient.builder().requestFactory(requests).requestInterceptor(BoundedHttp.responseLimit(MAX_RESPONSE_BYTES)))
                .responseErrorHandler(new SafeErrors()).build();
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(properties.getModel())
                        .maxCompletionTokens(properties.getMaxCompletionTokens()).reasoningEffort(REASONING_EFFORT)
                        .extraBody(Map.of(INCLUDE_REASONING,REASONING_ENABLED))
                        .internalToolExecutionEnabled(false).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(SINGLE_ATTEMPT).build()).build();
    }

    static final class SafeErrors implements ResponseErrorHandler {
        @Override public boolean hasError(ClientHttpResponse response) throws IOException {
            // Redirects must not forward a provider credential to another host.
            return !response.getStatusCode().is2xxSuccessful();
        }
        @Override public void handleError(URI url,HttpMethod method,ClientHttpResponse response) throws IOException {
            HttpStatusCode status = response.getStatusCode();
            if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value())
                throw new ModelFailure(ModelFailure.Type.AI_RATE_LIMITED,
                        retryAfter(response.getHeaders().getFirst(RETRY_AFTER_HEADER)));
            if (status.value() == HttpStatus.REQUEST_TIMEOUT.value() || status.value() == HttpStatus.GATEWAY_TIMEOUT.value())
                throw new ModelFailure(ModelFailure.Type.AI_TIMEOUT);
            throw new ModelFailure(ModelFailure.Type.AI_PROVIDER_UNAVAILABLE);
        }
    }
    static Integer retryAfter(String value) {
        if (value == null) return null;
        try {
            long seconds;
            try { seconds = Long.parseLong(value.trim()); }
            catch (NumberFormatException date) {
                seconds = Duration.between(ZonedDateTime.now(ZoneOffset.UTC),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).getSeconds();
            }
            return (int) Math.max(0, Math.min(seconds, MAX_RETRY_AFTER_SECONDS));
        } catch (RuntimeException invalid) { return null; }
    }
}
