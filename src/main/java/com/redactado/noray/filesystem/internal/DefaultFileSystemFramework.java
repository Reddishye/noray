package com.redactado.noray.filesystem.internal;

import com.redactado.noray.filesystem.api.FileSystemFramework;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.vfs.VirtualFileSystem;
import com.redactado.noray.filesystem.volume.VolumeDriver;
import com.redactado.noray.filesystem.volume.VolumeManager;

import java.util.Objects;

public final class DefaultFileSystemFramework implements FileSystemFramework {

    private final VolumeManager volumes;
    private final VirtualFileSystem vfs;
    private final ObjectStore objects;
    private final FileSystemService files;

    public DefaultFileSystemFramework(
            VolumeManager volumes,
            VirtualFileSystem vfs,
            ObjectStore objects,
            FileSystemService files
    ) {
        this.volumes = Objects.requireNonNull(volumes, "volumes");
        this.vfs = Objects.requireNonNull(vfs, "vfs");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.files = Objects.requireNonNull(files, "files");
    }

    @Override public VolumeManager volumes() { return volumes; }
    @Override public VirtualFileSystem vfs() { return vfs; }
    @Override public ObjectStore objects() { return objects; }
    @Override public FileSystemService files() { return files; }

    @Override
    public void registerVolumeDriver(VolumeDriver driver) {
        volumes.registerDriver(driver);
    }
}