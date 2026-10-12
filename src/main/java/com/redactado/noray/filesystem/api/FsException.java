package com.redactado.noray.filesystem.api;

public final class FsException extends RuntimeException {
    private final FsErrorCode code;

    public FsException(FsErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public FsException(FsErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public FsErrorCode code() {
        return code;
    }
}
