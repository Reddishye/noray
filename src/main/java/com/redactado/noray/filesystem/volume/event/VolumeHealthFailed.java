package com.redactado.noray.filesystem.volume.event;

import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.kernel.api.Event;

import java.time.Instant;
import java.util.Objects;

public record VolumeHealthFailed(VolumeId id, String details, Instant timestamp) implements Event {
    public VolumeHealthFailed {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public VolumeHealthFailed(VolumeId id, String details) {
        this(id, details, Instant.now());
    }
}
