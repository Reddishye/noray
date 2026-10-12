package com.redactado.noray.filesystem.vfs;

import com.redactado.noray.filesystem.api.DirectoryEntry;

import java.util.List;
import java.util.Optional;

public interface VirtualFileSystem {
    void mount(Mount mount);     // fails ALREADY_EXISTS if mountPoint taken
    void unmount(VfsPath mountPoint);  // does not detach volume
    Optional<Mount> findMount(VfsPath path); // longest prefix
    List<Mount> mounts();
    List<DirectoryEntry> list(VfsPath path); // Phase 3: only synthetic dirs / /vol /mnt /repo and mount points
}
