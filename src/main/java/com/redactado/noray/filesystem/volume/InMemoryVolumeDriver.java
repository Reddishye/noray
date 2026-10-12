package com.redactado.noray.filesystem.volume;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.api.FsException;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryVolumeDriver implements VolumeDriver {
    private final ConcurrentHashMap<VolumeId, Object> payloadStore = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public VolumeKind kind() {
        return VolumeKind.MEMORY;
    }

    @Override
    public boolean supports(Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        String kind = metadata.getString("kind", null);
        return kind == null || "memory".equalsIgnoreCase(kind);
    }

    @Override
    public Volume probe(Metadata metadata) throws FsException {
        Objects.requireNonNull(metadata, "metadata");
        String uuidStr = metadata.getString("uuid", null);
        UUID uuid;
        if (uuidStr != null) {
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                uuid = UUID.randomUUID();
            }
        } else {
            uuid = UUID.randomUUID();
        }
        VolumeId volumeId = new VolumeId(uuid);
        boolean writable = Boolean.parseBoolean(metadata.getString("writable", "true"));
        return new SimpleVolume(volumeId, VolumeKind.MEMORY, VolumeState.PROBING, writable, metadata, this);
    }

    @Override
    public void attach(Volume volume) throws FsException {
        Objects.requireNonNull(volume, "volume");
        payloadStore.putIfAbsent(volume.id(), new Object());
    }

    @Override
    public void detach(Volume volume) throws FsException {
        Objects.requireNonNull(volume, "volume");
        payloadStore.remove(volume.id());
    }

    @Override
    public boolean isHealthy(Volume volume) {
        return true;
    }
}
