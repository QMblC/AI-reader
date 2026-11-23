package com.aireader.application.port;

public final class RequestQuotaExceededException extends RuntimeException {
    private final String code;
    private final long retryAfterSeconds;

    public RequestQuotaExceededException(String code, long retryAfterSeconds) {
        super(code);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
