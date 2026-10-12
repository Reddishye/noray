package com.redactado.noray.filesystem.vfs;

import com.redactado.noray.filesystem.volume.VolumeId;

import java.util.Objects;

public record Mount(String name, VfsPath mountPoint, VolumeId volumeId, String options) {
    public Mount {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mountPoint, "mountPoint");
        Objects.requireNonNull(volumeId, "volumeId");
        if (options == null) {
            options = "";
        }
    }
}
