package com.example.ai_service.model;

public interface LanguageModel {
    <T> ModelResult<T> generate(ModelTask<T> task);
}
