package com.example.ai_service.model;

public record ModelResult<T>(T output, String model, long durationMs, Integer promptTokens,
                              Integer completionTokens, Integer totalTokens) {
    @Override public String toString() { return "ModelResult[output=REDACTED, model=" + model + "]"; }
}
