package com.redactado.noray.service;

@FunctionalInterface
public interface ServiceWorker {
    void run(ServiceExecutionContext context) throws Exception;
}
