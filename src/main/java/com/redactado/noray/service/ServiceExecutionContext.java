package com.redactado.noray.service;

public interface ServiceExecutionContext {
    String getServiceName();
    boolean isStopRequested();
    void log(String message);
}
