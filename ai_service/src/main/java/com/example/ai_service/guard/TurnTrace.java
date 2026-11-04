package com.example.ai_service.guard;

import java.util.concurrent.atomic.AtomicInteger;

/** No prompt, token/session capability, headers or user profile is retained here. */
public final class TurnTrace {
    private static final ThreadLocal<TurnTrace> CURRENT=new ThreadLocal<>();
    public final AtomicInteger modelCalls=new AtomicInteger();
    public final AtomicInteger httpCalls=new AtomicInteger();
    public volatile Integer promptTokens,completionTokens,totalTokens;
    public volatile String model="none";
    public static TurnTrace current() { return CURRENT.get(); }
    public static AutoCloseable bind(TurnTrace trace) {
        var previous=CURRENT.get(); CURRENT.set(trace);
        return ()->{if(previous==null) CURRENT.remove();else CURRENT.set(previous);};
    }
}
