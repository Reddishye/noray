package com.redactado.noray.filesystem.vfs;

import com.redactado.noray.filesystem.api.DirectoryEntry;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NodeType;

import java.util.*;

public class DefaultVirtualFileSystem implements VirtualFileSystem {
    public static final String MNT = "mnt";
    public static final String VOL = "vol";
    public static final String REPO = "repo";

    /** Top-level synthetic namespaces directly under the root. */
    public static final Set<String> ROOT_SYNTHETIC = Set.of(VOL, MNT, REPO);

    /** Observer of mount table changes, e.g. to persist them. */
    public interface MountListener {
        /** Called after every successful change with the resulting mounts. May throw to veto the change. */
        void mountsChanged(List<Mount> mounts);
    }

    private final MountTable mountTable = new MountTable();
    private final List<MountListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addMountListener(MountListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeMountListener(MountListener listener) {
        listeners.remove(listener);
    }

    @Override
    public synchronized void mount(Mount mount) {
        mountTable.addMount(mount);
        try {
            notifyListeners();
        } catch (RuntimeException e) {
            mountTable.removeMount(mount.mountPoint()); // keep memory and persisted state consistent
            throw e;
        }
    }

    @Override
    public synchronized void unmount(VfsPath mountPoint) {
        Mount existing = mountTable.list().stream()
                .filter(m -> m.mountPoint().equals(mountPoint))
                .findFirst().orElse(null);
        mountTable.removeMount(mountPoint);
        try {
            notifyListeners();
        } catch (RuntimeException e) {
            if (existing != null) {
                mountTable.addMount(existing);
            }
            throw e;
        }
    }

    private void notifyListeners() {
        if (listeners.isEmpty()) {
            return;
        }
        List<Mount> snapshot = mountTable.list();
        for (MountListener l : listeners) {
            l.mountsChanged(snapshot);
        }
    }

    @Override
    public Optional<Mount> findMount(VfsPath path) {
        return mountTable.findMount(path);
    }

    @Override
    public List<Mount> mounts() {
        return mountTable.list();
    }

    @Override
    public List<DirectoryEntry> list(VfsPath path) {
        Objects.requireNonNull(path, "path");

        if (path.isRoot()) {
            return List.of(
                    new DirectoryEntry(MNT, NodeType.DIRECTORY),
                    new DirectoryEntry(REPO, NodeType.DIRECTORY),
                    new DirectoryEntry(VOL, NodeType.DIRECTORY)
            );
        }

        List<String> segs = path.segments();
        String top = segs.get(0);

        if (!ROOT_SYNTHETIC.contains(top)) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Directory not found: " + path);
        }

        Map<String, DirectoryEntry> entries = new LinkedHashMap<>();

        // If path is e.g. /mnt, find direct child mounts or subdirectories under /mnt
        for (Mount m : mountTable.list()) {
            List<String> mSegs = m.mountPoint().segments();
            if (mSegs.size() > segs.size()) {
                boolean prefixMatches = true;
                for (int i = 0; i < segs.size(); i++) {
                    if (!segs.get(i).equals(mSegs.get(i))) {
                        prefixMatches = false;
                        break;
                    }
                }
                if (prefixMatches) {
                    String childName = mSegs.get(segs.size());
                    entries.putIfAbsent(childName, new DirectoryEntry(childName, NodeType.DIRECTORY));
                }
            }
        }

        return List.copyOf(entries.values());
    }
}
