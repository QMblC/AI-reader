package com.aireader.application.port;

/** Adapters translate provider failures to this provider-independent exception. */
public class LlmUnavailableException extends RuntimeException {
    private final Integer providerStatus;

    public LlmUnavailableException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public LlmUnavailableException(String message, Throwable cause, Integer providerStatus) {
        super(message, cause);
        this.providerStatus = providerStatus;
    }

    public Integer providerStatus() {
        return providerStatus;
    }
}
