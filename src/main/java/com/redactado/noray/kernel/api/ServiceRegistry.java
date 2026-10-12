package com.redactado.noray.kernel.api;

import java.util.Optional;

public interface ServiceRegistry {
    <T> void register(Class<T> serviceClass, T implementation);
    <T> Optional<T> get(Class<T> serviceClass);
    <T> T require(Class<T> serviceClass);
    <T> void unregister(Class<T> serviceClass);
    boolean contains(Class<?> serviceClass);
}