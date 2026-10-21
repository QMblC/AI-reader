package com.aireader.application.port;

/** Adapters translate provider failures to this provider-independent exception. */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
