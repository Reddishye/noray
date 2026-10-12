package com.redactado.noray.sandbox;

/** The operating system cannot provide the isolation that was asked for. Nothing was run. */
public final class SandboxUnavailableException extends RuntimeException {
    public SandboxUnavailableException(String message) {
        super(message);
    }
}
