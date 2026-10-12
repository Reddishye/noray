package com.redactado.noray.filesystem.volume.event;

import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.filesystem.volume.VolumeKind;
import com.redactado.noray.kernel.api.Event;

import java.time.Instant;
import java.util.Objects;

public record VolumeAttached(VolumeId id, VolumeKind kind, Instant timestamp) implements Event {
    public VolumeAttached {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public VolumeAttached(VolumeId id, VolumeKind kind) {
        this(id, kind, Instant.now());
    }
}
