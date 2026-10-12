package com.redactado.noray.filesystem.volume.event;

import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.kernel.api.Event;

import java.time.Instant;
import java.util.Objects;

public record VolumeDetached(VolumeId id, Instant timestamp) implements Event {
    public VolumeDetached {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public VolumeDetached(VolumeId id) {
        this(id, Instant.now());
    }
}
