package com.aireader.application.port;

/** Signals an invalid model response without retaining the raw response in the error. */
public class InvalidLlmResponseException extends RuntimeException {
    public InvalidLlmResponseException() {
        super("LLM response does not match the analysis contract");
    }
}
