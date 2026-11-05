package com.example.ai_service.guard;

import com.example.ai_service.model.*;
import org.springframework.ai.converter.BeanOutputConverter;
import java.nio.charset.StandardCharsets;

public final class BudgetedLanguageModel implements LanguageModel {
    private final LanguageModel delegate;
    private final UsageBudget budget;
    private final GuardProperties limits;
    private final int outputTokens;
    private final String modelId;
    public BudgetedLanguageModel(LanguageModel delegate,UsageBudget budget,GuardProperties limits,int outputTokens,String modelId) {
        this.delegate=delegate;this.budget=budget;this.limits=limits;this.outputTokens=outputTokens;this.modelId=modelId;
    }
    @Override public <T> ModelResult<T> generate(ModelTask<T> task) {
        TurnExecutor.check();
        var trace=TurnTrace.current();
        if(trace!=null && trace.modelCalls.get()>=1) throw new ModelFailure(ModelFailure.Type.AI_INVALID_OUTPUT);
        String schema=new BeanOutputConverter<>(task.outputType()).getJsonSchema();
        int bytes=task.systemText().getBytes(StandardCharsets.UTF_8).length+task.userText().getBytes(StandardCharsets.UTF_8).length+schema.getBytes(StandardCharsets.UTF_8).length;
        if(bytes>limits.promptUtf8Bytes()) throw new ModelFailure(ModelFailure.Type.AI_INPUT_TOO_LARGE);
        // Heuristic reservation, NOT a model tokenizer or a guaranteed upper bound on actual billing.
        int estimate=(bytes+1)/2+outputTokens+256;
        var reserved=budget.reserve(estimate);
        if(trace!=null) { trace.modelCalls.incrementAndGet();trace.model=modelId; }
        try {
            var result=delegate.generate(task);
            budget.settle(reserved,result.totalTokens());
            if(trace!=null) { trace.promptTokens=result.promptTokens();trace.completionTokens=result.completionTokens();trace.totalTokens=result.totalTokens(); }
            return result;
        } catch(ModelFailure failure) {
            if(failure.type()==ModelFailure.Type.AI_RATE_LIMITED) budget.coolDown(failure.retryAfterSeconds());
            throw failure;
        }
    }
}
