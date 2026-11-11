package com.aireader.application.port;

public final class IllustrationException extends RuntimeException {
    private final String code;
    private final int status;
    public IllustrationException(String code, int status) {
        super(code);
        this.code = code;
        this.status = status;
    }
    public String code() { return code; }
    public int status() { return status; }
}
