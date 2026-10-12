package com.redactado.noray.net.internal;

import com.redactado.noray.net.spi.EndpointDriver;
import com.redactado.noray.net.spi.PathHint;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EndpointDriverRegistry {
    private final List<EndpointDriver> drivers = new CopyOnWriteArrayList<>();

    public void register(EndpointDriver driver) {
        Objects.requireNonNull(driver, "driver");
        drivers.add(driver);
    }

    public void unregister(EndpointDriver driver) {
        drivers.remove(driver);
    }

    public Optional<EndpointDriver> find(PathHint hint) {
        Objects.requireNonNull(hint, "hint");
        return drivers.stream()
                .filter(d -> d.supports(hint))
                .findFirst();
    }

    public List<EndpointDriver> list() {
        return List.copyOf(drivers);
    }
}