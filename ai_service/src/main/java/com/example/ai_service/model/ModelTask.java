package com.example.ai_service.model;

import com.example.ai_service.conversation.ChatModels;

/** Server-created task only. Client requests must never supply system text, schema or model settings. */
public record ModelTask<T>(String systemText, String userText, Class<T> outputType) {
    /** Twice the 16000 code-point request cap leaves room for the JSON context wrapped around a maximal message. */
    private static final int MAX_USER_POINTS=ChatModels.MAX_MESSAGE_POINTS*2;
    public ModelTask {
        if (systemText == null || systemText.isBlank() || systemText.length() > 8000
                || userText == null || userText.isBlank() || userText.codePointCount(0, userText.length()) > MAX_USER_POINTS
                || outputType == null)
            throw new IllegalArgumentException("Invalid model task.");
    }
    @Override public String toString() { return "ModelTask[content=REDACTED]"; }
}
