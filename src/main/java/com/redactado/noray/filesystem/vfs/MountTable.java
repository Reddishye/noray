package com.redactado.noray.filesystem.vfs;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class MountTable {
    private final ConcurrentHashMap<VfsPath, Mount> mounts = new ConcurrentHashMap<>();

    public void addMount(Mount mount) {
        Objects.requireNonNull(mount, "mount");
        Mount previous = mounts.putIfAbsent(mount.mountPoint(), mount);
        if (previous != null) {
            throw new FsException(FsErrorCode.ALREADY_EXISTS, "Mount point already in use: " + mount.mountPoint());
        }
    }

    public void removeMount(VfsPath mountPoint) {
        Objects.requireNonNull(mountPoint, "mountPoint");
        Mount removed = mounts.remove(mountPoint);
        if (removed == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Mount point not found: " + mountPoint);
        }
    }

    public Optional<Mount> findMount(VfsPath path) {
        Objects.requireNonNull(path, "path");
        return mounts.values().stream()
                .filter(m -> isPrefixOf(m.mountPoint(), path))
                .max(Comparator.comparingInt(m -> m.mountPoint().segments().size()));
    }

    public List<Mount> list() {
        return List.copyOf(mounts.values());
    }

    private static boolean isPrefixOf(VfsPath prefix, VfsPath path) {
        if (prefix.isRoot()) {
            return true;
        }
        List<String> prefixSegs = prefix.segments();
        List<String> pathSegs = path.segments();
        if (prefixSegs.size() > pathSegs.size()) {
            return false;
        }
        for (int i = 0; i < prefixSegs.size(); i++) {
            if (!prefixSegs.get(i).equals(pathSegs.get(i))) {
                return false;
            }
        }
        return true;
    }
}
