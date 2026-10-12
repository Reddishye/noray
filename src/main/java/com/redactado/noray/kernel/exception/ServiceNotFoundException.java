package com.redactado.noray.kernel.exception;

public class ServiceNotFoundException extends KernelException {
    public ServiceNotFoundException(Class<?> serviceClass) {
        super("Service not registered in ServiceRegistry: " + serviceClass.getName());
    }
}