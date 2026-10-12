package com.redactado.noray.filesystem.volume.event;

import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.filesystem.volume.VolumeState;
import com.redactado.noray.kernel.api.Event;

import java.time.Instant;
import java.util.Objects;

public record VolumeStateChanged(VolumeId id, VolumeState from, VolumeState to, Instant timestamp) implements Event {
    public VolumeStateChanged {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public VolumeStateChanged(VolumeId id, VolumeState from, VolumeState to) {
        this(id, from, to, Instant.now());
    }
}
