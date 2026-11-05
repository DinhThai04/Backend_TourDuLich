package com.example.ai_service.model;

import com.example.ai_service.config.AiProperties;
import com.example.ai_service.probe.ProbeRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Real Spring AI + HTTP adapter; only the upstream is a loopback stub. No Groq key/network/DB. */
class GroqAdapterTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private ValidatorFactory validators;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> retryAfter = new AtomicReference<>();
    private final AtomicLong delay = new AtomicLong();

    @BeforeEach void start() throws Exception {
        validators = Validation.buildDefaultValidatorFactory();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (delay.get() > 0) Thread.sleep(delay.get());
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (retryAfter.get() != null) exchange.getResponseHeaders().set("Retry-After", retryAfter.get());
                if (status.get() == 302) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/credential-trap");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        completion("{\"brand\":\"SGU Tour\",\"language\":\"vi\",\"greeting\":\"Xin chào! Bạn muốn đi đâu?\"}", "stop");
    }
    @AfterEach void stop() {
        server.stop(0);
        executor.shutdownNow();
        validators.close();
    }
    private LanguageModel adapter(Duration timeout) {
        var properties = new AiProperties();
        // Only tests construct a loopback transport; production validates Groq's exact HTTPS base URL.
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/openai");
        properties.setApiKey("test-key-not-a-real-secret");
        properties.setReadTimeout(timeout);
        return new SpringAiLanguageModel(GroqChatModelFactory.create(properties), properties.getModel(), json, validators.getValidator());
    }
    private LanguageModel adapter() { return adapter(Duration.ofSeconds(2)); }
    private void completion(String content, String finishReason) throws Exception {
        body.set(json.writeValueAsString(Map.of("id", "stub-id", "object", "chat.completion", "created", 1,
                "model", "openai/gpt-oss-120b", "choices", java.util.List.of(Map.of("index", 0,
                        "message", Map.of("role", "assistant", "content", content), "finish_reason", finishReason)),
                "usage", Map.of("prompt_tokens", 50, "completion_tokens", 20, "total_tokens", 70))));
    }
    private ModelFailure failure(LanguageModel model) {
        return catchThrowableOfType(() -> model.generate(ProbeRunner.task()), ModelFailure.class);
    }
    @Test void sendsExactGroqPathAndStructuredSchemaOnce() throws Exception {
        var result = adapter().generate(ProbeRunner.task());
        assertThat(result.output().brand()).isEqualTo("SGU Tour");
        assertThat(result.output().greeting()).contains("Xin chào");
        assertThat(result.totalTokens()).isEqualTo(70);
        assertThat(result.promptTokens()).isEqualTo(50);
        assertThat(result.completionTokens()).isEqualTo(20);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(path.get()).isEqualTo("/openai/v1/chat/completions");
        assertThat(authorization.get()).isEqualTo("Bearer test-key-not-a-real-secret");
        JsonNode request = json.readTree(received.get());
        assertThat(request.path("model").asText()).isEqualTo("openai/gpt-oss-120b");
        assertThat(request.path("max_completion_tokens").asInt()).isEqualTo(1024);
        assertThat(request.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(request.path("include_reasoning").asBoolean(true)).isFalse();
        assertThat(request.has("reasoning_format")).isFalse();
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.has("tools")).isFalse();
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_schema");
        var schema = request.path("response_format").path("json_schema");
        assertThat(schema.path("strict").asBoolean()).isTrue();
        assertThat(schema.path("schema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("schema").path("required")).hasSize(3);
    }
    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 500, 503, 302})
    void upstreamErrorsNeverBecomeWebsiteUnauthorizedOrLeakBody(int errorStatus) {
        status.set(errorStatus);
        body.set("{\"error\":\"fake-upstream-secret and private user prompt\"}");
        var failure = failure(adapter());
        assertThat(failure.type()).isEqualTo(ModelFailure.Type.AI_PROVIDER_UNAVAILABLE);
        assertThat(failure.httpStatus()).isEqualTo(503);
        assertThat(failure.getMessage()).doesNotContain("fake-upstream", "private user");
        assertThat(failure.getCause()).isNull();
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void rateLimitPreservesRetryAfterAndDoesNotRetry() {
        status.set(429); retryAfter.set("17");
        var failure = failure(adapter());
        assertThat(failure.type()).isEqualTo(ModelFailure.Type.AI_RATE_LIMITED);
        assertThat(failure.retryAfterSeconds()).isEqualTo(17);
        assertThat(failure.retryable()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(ints = {408, 504})
    void providerTimeout(int timeoutStatus) {
        status.set(timeoutStatus);
        assertThat(failure(adapter()).type()).isEqualTo(ModelFailure.Type.AI_TIMEOUT);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void networkReadTimeoutIsBoundedAndNotRetried() {
        delay.set(1200);
        long started = System.nanoTime();
        assertThat(failure(adapter(Duration.ofMillis(150))).type()).isEqualTo(ModelFailure.Type.AI_TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {
            "not json", "null", "{}", "[]",
            "{\"brand\":\"SGU Tour\",\"language\":\"vi\"}",
            "{\"brand\":\"SGU Tour\",\"language\":\"vi\",\"greeting\":\"\"}",
            "{\"brand\":\"Other Shop\",\"language\":\"vi\",\"greeting\":\"Hello\"}",
            "{\"brand\":\"SGU Tour\",\"language\":\"en\",\"greeting\":\"Hello\"}",
            "{\"brand\":1,\"language\":\"vi\",\"greeting\":\"Hello\"}",
            "{\"brand\":\"SGU Tour\",\"language\":\"vi\",\"greeting\":\"Hello\",\"extra\":true}",
            "{\"brand\":\"SGU Tour\",\"brand\":\"SGU Tour\",\"language\":\"vi\",\"greeting\":\"Hello\"}",
            "{\"brand\":\"SGU Tour\",\"language\":\"vi\",\"greeting\":\"Hello\"} {}"
    })
    void invalidStructuredOutputRejectedWithoutRepairCalls(String content) throws Exception {
        completion(content, "stop");
        assertThat(failure(adapter()).type()).isEqualTo(ModelFailure.Type.AI_INVALID_OUTPUT);
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"length", "content_filter", "tool_calls"})
    void unfinishedOrRefusedOrToolResponseIsNotSuccess(String finishReason) throws Exception {
        completion("{\"brand\":\"SGU Tour\",\"language\":\"vi\",\"greeting\":\"Hello\"}", finishReason);
        assertThat(failure(adapter()).type()).isEqualTo(ModelFailure.Type.AI_INVALID_OUTPUT);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void malformedEnvelopeIsInvalidOutput() {
        body.set("broken-json");
        assertThat(failure(adapter()).type()).isEqualTo(ModelFailure.Type.AI_INVALID_OUTPUT);
    }
    @Test void completionWithoutUsageHasNullableTokenCounters() throws Exception {
        var envelope = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(body.get());
        envelope.remove("usage"); body.set(envelope.toString());
        assertThat(adapter().generate(ProbeRunner.task()).totalTokens()).isNull();
    }
    @Test void simultaneousCallsAreRejectedBeforeSecondHttpRequest() throws Exception {
        var model = adapter(); delay.set(600);
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = worker.submit(() -> model.generate(ProbeRunner.task()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(failure(model)).satisfies(busy->{assertThat(busy.type()).isEqualTo(ModelFailure.Type.AI_RATE_LIMITED);
                assertThat(busy.retryAfterSeconds()).isPositive();});
            assertThat(first.get(3, TimeUnit.SECONDS).output().brand()).isEqualTo("SGU Tour");
            assertThat(calls.get()).isEqualTo(1);
        }
    }
    @Test void permitIsReleasedAfterFailure() {
        var model = adapter(); status.set(429);
        failure(model); failure(model);
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void oversizedModelContentRejected() throws Exception {
        completion("x".repeat(16385), "stop");
        assertThat(failure(adapter()).type()).isEqualTo(ModelFailure.Type.AI_INVALID_OUTPUT);
    }
    @Test void modelHtmlIsNotAcceptedAsGreeting() throws Exception {
        completion("{\"brand\":\"SGU Tour\",\"language\":\"vi\",\"greeting\":\"<b>Xin chào</b>\"}", "stop");
        assertThat(failure(adapter()).type()).isEqualTo(ModelFailure.Type.AI_INVALID_OUTPUT);
    }
    @Test void malformedRetryAfterDoesNotInventDelay() {
        status.set(429); retryAfter.set("unknown");
        assertThat(failure(adapter()).retryAfterSeconds()).isNull();
    }
    @Test void structuredConversationPlanUsesNestedSchemaAndValidatesDto() throws Exception {
        completion("{\"intent\":\"SEARCH_TOURS\",\"reset\":false,\"changes\":[{\"field\":\"MAX_BUDGET_VND\",\"action\":\"SET\",\"value\":\"3000000\"}],\"positions\":[],\"clarification\":\"NONE\"}","stop");
        var task=new ModelTask<>("Return a validated SGU Tour plan, JSON only","budget",com.example.ai_service.conversation.ChatModels.Plan.class);
        var result=adapter().generate(task);
        assertThat(result.output().changes().getFirst().value()).isEqualTo("3000000");
        var schema=json.readTree(received.get()).path("response_format").path("json_schema").path("schema");
        assertThat(schema.path("required")).hasSize(5);
        completion("{\"intent\":\"SEARCH_TOURS\",\"reset\":false,\"changes\":[{\"field\":\"MAX_BUDGET_VND\",\"action\":\"SET\",\"value\":null}],\"positions\":[],\"clarification\":\"NONE\"}","stop");
        assertThatThrownBy(()->adapter().generate(task)).hasMessage("AI_INVALID_OUTPUT");
    }
    @Test void toolCallWithStopFinishIsRejectedAndNeverExecuted() throws Exception {
        var mapper=new ObjectMapper();
        var parsed=mapper.readTree(body.get());
        ((com.fasterxml.jackson.databind.node.ObjectNode)parsed.path("choices").get(0).path("message"))
            .putArray("tool_calls").addObject().put("id","call_sql").put("type","function").putObject("function")
            .put("name","execute_sql").put("arguments","{\"sql\":\"DROP TABLE users\"}");
        body.set(mapper.writeValueAsString(parsed));
        assertThat(failure(adapter()).type()).isEqualTo(ModelFailure.Type.AI_INVALID_OUTPUT);
        assertThat(calls.get()).isEqualTo(1);assertThat(mapper.readTree(received.get()).has("tools")).isFalse();
    }
}
