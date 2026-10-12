package com.redactado.noray.filesystem.config;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.MetadataItem;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.filesystem.volume.VolumeManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps the operator-defined volumes and the mount table durable.
 *
 * <ul>
 *   <li>{@link #addVolume}/{@link #removeVolume} attach/detach a volume <em>and</em> remember it.
 *       Volumes attached any other way (hot-plug, tests) are not persisted.</li>
 *   <li>Every mount or unmount on the VFS is persisted automatically.</li>
 *   <li>{@link #restore()} re-creates all of it at boot. A volume that cannot be attached right now
 *       (e.g. a disk that is not plugged in) does not stop the others and stays defined.</li>
 * </ul>
 * Shutting the node down never edits the stored configuration.
 */
public final class FsConfigService {

    /** Outcome of {@link #restore()}. */
    public record RestoreReport(List<VolumeId> attached, Map<VolumeId, String> failedVolumes,
                                List<Mount> mounted, Map<Mount, String> failedMounts) {
    }

    private final VolumeManager volumes;
    private final DefaultVirtualFileSystem vfs;
    private final FsConfigStore store;

    private final Map<VolumeId, Metadata> definitions = new LinkedHashMap<>();
    /** Mounts from the stored configuration that could not be restored yet; they stay in the file meanwhile. */
    private final List<Mount> pendingMounts = new ArrayList<>();
    private boolean restoring;
    private boolean listening;

    public FsConfigService(VolumeManager volumes, DefaultVirtualFileSystem vfs, FsConfigStore store) {
        this.volumes = Objects.requireNonNull(volumes, "volumes");
        this.vfs = Objects.requireNonNull(vfs, "vfs");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Loads the stored configuration, attaches its volumes and re-creates its mounts. */
    public synchronized RestoreReport restore() {
        FsConfig config = store.load();
        definitions.clear();
        definitions.putAll(config.volumes());

        List<VolumeId> attached = new ArrayList<>();
        Map<VolumeId, String> failedVolumes = new LinkedHashMap<>();
        List<Mount> mounted = new ArrayList<>();
        Map<Mount, String> failedMounts = new LinkedHashMap<>();

        restoring = true;
        try {
            for (Map.Entry<VolumeId, Metadata> e : config.volumes().entrySet()) {
                try {
                    volumes.attach(withUuid(e.getValue(), e.getKey()));
                    attached.add(e.getKey());
                } catch (RuntimeException ex) {
                    failedVolumes.put(e.getKey(), String.valueOf(ex.getMessage()));
                }
            }
            for (Mount m : config.mounts()) {
                try {
                    vfs.mount(m);
                    mounted.add(m);
                } catch (RuntimeException ex) {
                    failedMounts.put(m, String.valueOf(ex.getMessage()));
                }
            }
        } finally {
            restoring = false;
        }

        pendingMounts.clear();
        pendingMounts.addAll(failedMounts.keySet());

        // From now on every mount change is persisted.
        if (!listening) {
            vfs.addMountListener(this::onMountsChanged);
            listening = true;
        }
        return new RestoreReport(attached, failedVolumes, mounted, failedMounts);
    }

    /** What a retry achieved. */
    public record RetryReport(List<VolumeId> attached, List<Mount> mounted, int stillPending) {
    }

    /**
     * Tries again what {@link #restore()} could not do (a remote peer that was not up yet, a disk plugged in later):
     * attaches the defined volumes that are not attached and restores the mounts that were waiting for them.
     */
    public synchronized RetryReport retryPending() {
        List<VolumeId> attached = new ArrayList<>();
        restoring = true;
        try {
            for (Map.Entry<VolumeId, Metadata> e : definitions.entrySet()) {
                if (volumes.get(e.getKey()).isPresent()) continue;
                try {
                    volumes.attach(withUuid(e.getValue(), e.getKey()));
                    attached.add(e.getKey());
                } catch (RuntimeException ex) {
                    // still not possible; the next round tries again
                }
            }
            List<Mount> mounted = new ArrayList<>();
            for (Mount m : new ArrayList<>(pendingMounts)) {
                try {
                    vfs.mount(m);
                    pendingMounts.remove(m);
                    mounted.add(m);
                } catch (RuntimeException ex) {
                    // its volume is still missing
                }
            }
            int pending = pendingMounts.size();
            for (VolumeId id : definitions.keySet()) {
                if (volumes.get(id).isEmpty()) pending++;
            }
            return new RetryReport(attached, mounted, pending);
        } finally {
            restoring = false;
        }
    }

    /** Attaches a volume and remembers its definition. Returns the attached volume. */
    public synchronized Volume addVolume(Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        Volume volume = volumes.attach(metadata);
        Metadata definition = withUuid(volume.metadata(), volume.id());
        Map<VolumeId, Metadata> previous = new LinkedHashMap<>(definitions);
        definitions.put(volume.id(), definition);
        try {
            persist(vfs.mounts());
        } catch (RuntimeException e) {
            definitions.clear();
            definitions.putAll(previous);
            try {
                volumes.detach(volume.id());
            } catch (RuntimeException ignored) {
                // best effort rollback
            }
            throw e;
        }
        return volume;
    }

    /** Detaches a volume and forgets its definition. Mounts that point to it are left untouched. */
    public synchronized void removeVolume(VolumeId id) {
        Objects.requireNonNull(id, "id");
        boolean defined = definitions.containsKey(id);
        if (!defined && volumes.get(id).isEmpty()) {
            throw new FsException(FsErrorCode.VOLUME_UNKNOWN, "Volume not found: " + id);
        }
        if (volumes.get(id).isPresent()) {
            volumes.detach(id);
        }
        if (defined) {
            Metadata removed = definitions.remove(id);
            try {
                persist(vfs.mounts());
            } catch (RuntimeException e) {
                definitions.put(id, removed);
                throw e;
            }
        }
    }

    /** Volume definitions currently persisted. */
    public synchronized Map<VolumeId, Metadata> definitions() {
        return Map.copyOf(definitions);
    }

    private synchronized void onMountsChanged(List<Mount> mounts) {
        if (restoring) {
            return;
        }
        persist(mounts);
    }

    private void persist(List<Mount> mounts) {
        // Only mounts of volumes the operator defined are durable; a mount of a volatile or hot-plugged
        // volume would point to nothing after the next boot.
        List<Mount> ordered = new ArrayList<>();
        for (Mount m : mounts) {
            if (definitions.containsKey(m.volumeId())) {
                ordered.add(m);
            }
        }
        for (Mount m : pendingMounts) {
            if (definitions.containsKey(m.volumeId())) {
                ordered.add(m); // not mounted yet, but still defined
            }
        }
        ordered.sort(java.util.Comparator.comparing(m -> m.mountPoint().toString()));
        store.save(new FsConfig(definitions, ordered));
    }

    private static Metadata withUuid(Metadata metadata, VolumeId id) {
        Metadata.Builder b = Metadata.builder();
        for (MetadataItem item : metadata.asMap().values()) {
            b.put(item);
        }
        b.put("uuid", id.value().toString());
        return b.build();
    }
}
