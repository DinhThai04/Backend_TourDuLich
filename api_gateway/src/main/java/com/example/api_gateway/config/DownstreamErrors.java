package com.example.api_gateway.config;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Classifies failures that mean "the downstream service is unreachable" rather than "the caller is wrong". */
final class DownstreamErrors {

    private DownstreamErrors() {
    }

    static boolean isUnavailable(Throwable error) {
        if (error instanceof WebClientRequestException) return true;
        if (error instanceof WebClientResponseException responseError) {
            return responseError.getStatusCode().is5xxServerError();
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof ConnectException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }
}
