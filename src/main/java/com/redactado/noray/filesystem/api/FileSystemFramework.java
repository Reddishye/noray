package com.redactado.noray.filesystem.api;

import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.vfs.VirtualFileSystem;
import com.redactado.noray.filesystem.volume.VolumeDriver;
import com.redactado.noray.filesystem.volume.VolumeManager;

/**
 * Public facade of the noray.fs mini-framework (Linux VFS analog).
 * Other modules CALL this. They do not import DefaultVolumeManager.
 */
public interface FileSystemFramework {

    VolumeManager volumes();

    VirtualFileSystem vfs();

    ObjectStore objects();

    FileSystemService files();

    void registerVolumeDriver(VolumeDriver driver);
}