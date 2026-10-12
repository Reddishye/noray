package com.redactado.noray.filesystem.volume;

import java.util.Objects;
import java.util.UUID;

public record VolumeId(UUID value) {
    public VolumeId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
