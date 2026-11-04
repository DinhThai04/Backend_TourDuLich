package com.example.ai_service.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * ChatErrors is scoped to the AI controllers, so it is only consulted once a handler method has been
 * chosen. A request that matches nothing — unknown path under /ai, wrong verb on a known path — would
 * otherwise fall through to Spring's default error body, which names the internal servlet path and
 * breaks the envelope every other service honours.
 */
@RestControllerAdvice
public class RoutingErrors {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ChatErrors.ErrorBody> missingResource() {
        return ChatErrors.response(404, "NOT_FOUND", false, null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ChatErrors.ErrorBody> unsupportedMethod() {
        return ChatErrors.response(405, "METHOD_NOT_ALLOWED", false, null, null);
    }
}
