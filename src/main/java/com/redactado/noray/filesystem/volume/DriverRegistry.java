package com.redactado.noray.filesystem.volume;

import com.redactado.noray.filesystem.Metadata;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DriverRegistry {
    private final List<VolumeDriver> drivers = new CopyOnWriteArrayList<>();

    public void register(VolumeDriver driver) {
        Objects.requireNonNull(driver, "driver");
        drivers.add(driver);
    }

    public void unregister(VolumeDriver driver) {
        drivers.remove(driver);
    }

    public Optional<VolumeDriver> findDriver(Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return drivers.stream()
                .filter(d -> d.supports(metadata))
                .findFirst();
    }

    public List<VolumeDriver> list() {
        return List.copyOf(drivers);
    }
}
