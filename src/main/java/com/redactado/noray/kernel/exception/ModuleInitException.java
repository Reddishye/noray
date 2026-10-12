package com.redactado.noray.kernel.exception;

public class ModuleInitException extends KernelException {
    public ModuleInitException(String moduleId, Throwable cause) {
        super("Failed to initialize module '" + moduleId + "'", cause);
    }
}