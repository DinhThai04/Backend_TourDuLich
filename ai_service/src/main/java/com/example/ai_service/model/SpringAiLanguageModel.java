package com.example.ai_service.model;

import com.example.ai_service.guard.TurnExecutor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import jakarta.validation.Validator;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.Semaphore;

public final class SpringAiLanguageModel implements LanguageModel {
    private static final int MAX_RESPONSE_CHARS=16384;
    private final ChatModel model;
    private final String modelId;
    private final ObjectMapper json;
    private final Validator validator;
    private final Semaphore inFlight = new Semaphore(1);

    public SpringAiLanguageModel(ChatModel model, String modelId, ObjectMapper mapper, Validator validator) {
        this.model = model;
        this.modelId = modelId;
        this.validator = validator;
        this.json = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        for (var type : List.of(LogicalType.Integer, LogicalType.Float, LogicalType.Textual, LogicalType.Boolean))
            json.coercionConfigFor(type).setCoercion(CoercionInputShape.String,
                    type == LogicalType.Textual ? CoercionAction.TryConvert : CoercionAction.Fail);
        json.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    @Override public <T> ModelResult<T> generate(ModelTask<T> task) {
        // The busy permit frees when the running call finishes, i.e. no later than the turn deadline.
        if (!inFlight.tryAcquire()) throw new ModelFailure(ModelFailure.Type.AI_RATE_LIMITED,
                Math.max(1, TurnExecutor.remainingSeconds()));
        long started = System.nanoTime();
        try {
            var converter = new BeanOutputConverter<>(task.outputType());
            var format = ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(ResponseFormat.JsonSchema.builder().name("sgu_tour_response")
                            .schema(converter.getJsonSchema()).strict(true).build()).build();
            var options = OpenAiChatOptions.builder().responseFormat(format).internalToolExecutionEnabled(false).build();
            var prompt = new Prompt(List.of(new SystemMessage(task.systemText()), new UserMessage(task.userText())), options);
            var response = model.call(prompt);
            if (response == null || response.getResults() == null || response.getResults().size() != 1)
                throw new ModelFailure(ModelFailure.Type.AI_INVALID_OUTPUT);
            var generation = response.getResult();
            if (generation.getMetadata() == null || !"STOP".equalsIgnoreCase(generation.getMetadata().getFinishReason())
                    || generation.getOutput() == null || generation.getOutput().hasToolCalls()
                    || (generation.getOutput().getMedia() != null && !generation.getOutput().getMedia().isEmpty()))
                throw new ModelFailure(ModelFailure.Type.AI_INVALID_OUTPUT);
            String content = generation.getOutput().getText();
            if (content == null || content.length() > MAX_RESPONSE_CHARS)
                throw new ModelFailure(ModelFailure.Type.AI_INVALID_OUTPUT);
            T output;
            try {
                output = json.readValue(content, task.outputType());
                if (output == null || !validator.validate(output).isEmpty())
                    throw new ModelFailure(ModelFailure.Type.AI_INVALID_OUTPUT);
            } catch (IOException invalid) {
                throw new ModelFailure(ModelFailure.Type.AI_INVALID_OUTPUT);
            }
            var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            if (usage instanceof EmptyUsage) usage = null;
            return new ModelResult<>(output, modelId, (System.nanoTime() - started) / 1_000_000,
                    usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens(),
                    usage == null ? null : usage.getTotalTokens());
        } catch (ModelFailure known) {
            throw known;
        } catch (ResourceAccessException failure) {
            throw new ModelFailure(isTimeout(failure) ? ModelFailure.Type.AI_TIMEOUT : ModelFailure.Type.AI_PROVIDER_UNAVAILABLE);
        } catch (RestClientException invalidEnvelope) {
            throw new ModelFailure(ModelFailure.Type.AI_INVALID_OUTPUT);
        } catch (RuntimeException failure) {
            throw new ModelFailure(ModelFailure.Type.AI_PROVIDER_UNAVAILABLE);
        } finally {
            inFlight.release();
        }
    }
    private boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return true;
        return false;
    }
}
