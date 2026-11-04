package com.example.ai_service.config;

import com.example.ai_service.model.*;
import com.example.ai_service.guard.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModelConfiguration {
    @Bean
    LanguageModel languageModel(AiProperties properties, ObjectMapper mapper, Validator validator,UsageBudget budget,GuardProperties limits) {
        properties.validate();
        if (!properties.isEnabled()) {
            return new LanguageModel() {
                @Override public <T> ModelResult<T> generate(ModelTask<T> task) {
                    throw new ModelFailure(ModelFailure.Type.AI_PROVIDER_NOT_CONFIGURED);
                }
            };
        }
        return new BudgetedLanguageModel(new SpringAiLanguageModel(GroqChatModelFactory.create(properties), properties.getModel(), mapper, validator),
                budget,limits,properties.getMaxCompletionTokens(),properties.getModel());
    }
}
