package com.redactado.noray.filesystem.driver.local;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.MetadataItem;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.LocalDirObjectStore;
import com.redactado.noray.filesystem.volume.SimpleVolume;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeDriver;
import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.filesystem.volume.VolumeKind;
import com.redactado.noray.filesystem.volume.VolumeState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable volume backed by a directory on the local disk ({@code kind=local}, {@code path=<dir>}).
 * Blobs are stored by {@link LocalDirObjectStore}, so they survive restarts.
 */
public final class LocalVolumeDriver implements VolumeDriver {

    @Override
    public String name() {
        return "local";
    }

    @Override
    public VolumeKind kind() {
        return VolumeKind.LOCAL;
    }

    @Override
    public boolean supports(Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return "local".equalsIgnoreCase(metadata.getString("kind", ""));
    }

    @Override
    public Volume probe(Metadata metadata) throws FsException {
        Objects.requireNonNull(metadata, "metadata");
        String raw = metadata.getString("path", "");
        if (raw.isBlank()) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "Local volume requires path");
        }
        Path path = Path.of(raw).toAbsolutePath().normalize();

        UUID uuid;
        String uuidStr = metadata.getString("uuid", null);
        if (uuidStr != null) {
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                uuid = UUID.randomUUID();
            }
        } else {
            uuid = UUID.randomUUID();
        }
        boolean writable = Boolean.parseBoolean(metadata.getString("writable", "true"));

        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "Local volume path is not a directory: " + path);
        }

        // Keep the resolved absolute path and the uuid in the volume metadata, so that a persisted
        // definition does not depend on the working directory of the next boot.
        Metadata.Builder resolved = Metadata.builder();
        for (MetadataItem item : metadata.asMap().values()) {
            resolved.put(item);
        }
        resolved.put("path", path.toString());
        resolved.put("uuid", uuid.toString());

        LocalDirObjectStore store = new LocalDirObjectStore(path);
        return new SimpleVolume(new VolumeId(uuid), VolumeKind.LOCAL, VolumeState.PROBING, writable,
                resolved.build(), this, store);
    }

    @Override
    public void attach(Volume volume) throws FsException {
    }

    @Override
    public void detach(Volume volume) throws FsException {
    }

    @Override
    public boolean isHealthy(Volume volume) {
        Path path = Path.of(volume.metadata().getString("path", ""));
        return Files.isDirectory(path) && (!volume.writable() || Files.isWritable(path));
    }
}
