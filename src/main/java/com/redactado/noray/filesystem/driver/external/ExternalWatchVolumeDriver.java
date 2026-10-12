package com.redactado.noray.filesystem.driver.external;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeDriver;
import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.filesystem.volume.VolumeKind;
import com.redactado.noray.filesystem.volume.VolumeState;
import com.redactado.noray.filesystem.volume.SimpleVolume;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public final class ExternalWatchVolumeDriver implements VolumeDriver {

    private final Path objectsDir;

    public ExternalWatchVolumeDriver(Path objectsDir) {
        this.objectsDir = objectsDir;
    }

    public ExternalWatchVolumeDriver() {
        this.objectsDir = null;
    }

    @Override
    public String name() {
        return "external";
    }

    @Override
    public VolumeKind kind() {
        return VolumeKind.EXTERNAL;
    }

    @Override
    public boolean supports(Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        String kind = metadata.getString("kind", "");
        return "external".equalsIgnoreCase(kind);
    }

    @Override
    public Volume probe(Metadata metadata) throws FsException {
        Objects.requireNonNull(metadata, "metadata");
        String uri = metadata.getString("uri", "");
        if (uri.isEmpty()) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "External volume requires uri");
        }
        Path path = Path.of(uri).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "External path is not a directory: " + path);
        }
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
        ObjectStore store = new com.redactado.noray.filesystem.store.LocalDirObjectStore(path);
        return new SimpleVolume(new VolumeId(uuid), VolumeKind.EXTERNAL, VolumeState.PROBING, writable, metadata, this, store);
    }

    @Override
    public void attach(Volume volume) throws FsException {
    }

    @Override
    public void detach(Volume volume) throws FsException {
    }

    @Override
    public boolean isHealthy(Volume volume) {
        return true;
    }
}