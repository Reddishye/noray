package com.redactado.noray.filesystem.config;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.volume.VolumeId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent filesystem configuration: the volumes an operator defined and the mounts in effect
 * (the equivalent of {@code /etc/fstab}). Hot-plugged volumes are not part of it.
 */
public record FsConfig(Map<VolumeId, Metadata> volumes, List<Mount> mounts) {

    public FsConfig {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(mounts, "mounts");
        volumes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(volumes));
        mounts = List.copyOf(mounts);
    }

    public static FsConfig empty() {
        return new FsConfig(Map.of(), List.of());
    }
}
