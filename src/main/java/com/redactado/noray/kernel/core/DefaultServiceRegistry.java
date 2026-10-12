package com.redactado.noray.kernel.core;

import com.redactado.noray.kernel.api.ServiceRegistry;
import com.redactado.noray.kernel.exception.ServiceNotFoundException;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultServiceRegistry implements ServiceRegistry {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    @Override
    public <T> void register(Class<T> serviceClass, T implementation) {
        Objects.requireNonNull(serviceClass, "Service class cannot be null");
        Objects.requireNonNull(implementation, "Implementation cannot be null");
        services.put(serviceClass, implementation);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(Class<T> serviceClass) {
        Objects.requireNonNull(serviceClass, "Service class cannot be null");
        return Optional.ofNullable((T) services.get(serviceClass));
    }

    @Override
    public <T> T require(Class<T> serviceClass) {
        return get(serviceClass).orElseThrow(() -> new ServiceNotFoundException(serviceClass));
    }

    @Override
    public <T> void unregister(Class<T> serviceClass) {
        Objects.requireNonNull(serviceClass, "Service class cannot be null");
        services.remove(serviceClass);
    }

    @Override
    public boolean contains(Class<?> serviceClass) {
        return services.containsKey(serviceClass);
    }
}