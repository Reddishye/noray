package com.redactado.noray.filesystem.vfs;

import com.redactado.noray.data.StoragePolicy;
import com.redactado.noray.data.manifest.FileRef;
import com.redactado.noray.filesystem.api.*;
import com.redactado.noray.filesystem.replica.ReplicaGroupId;
import com.redactado.noray.filesystem.replica.ReplicaPlanner;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeManager;
import com.redactado.noray.filesystem.volume.VolumeKind;
import com.redactado.noray.filesystem.volume.VolumeState;
import com.redactado.noray.filesystem.vfs.journal.NamespaceJournal;
import com.redactado.noray.filesystem.vfs.journal.NamespaceRecord;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

public class DefaultFileSystemService implements FileSystemService, StorageAdmin, NamespaceAdmin, AutoCloseable {
    private static final long COMPACT_MIN_RECORDS = 1000;

    private final NamespaceJournal journal;
    private final Predicate<VfsPath> persistFilter;
    private final VirtualFileSystem vfs;
    private final VolumeManager volumeManager;
    private final ObjectStore objectStore;
    private final ReplicaPlanner replicaPlanner;
    private final DataBackend backend;

    private final AtomicLong inodeSeq = new AtomicLong(1);
    private final Map<VfsPath, Inode> inodes = new ConcurrentHashMap<>();
    private final Map<VfsPath, Set<String>> children = new ConcurrentHashMap<>();
    private final Map<VfsPath, String> policies = new ConcurrentHashMap<>();
    /** Quota and permission attributes of directories ({@link NamespaceAdmin#KEYS}). */
    private final Map<VfsPath, Map<String, String>> attrs = new ConcurrentHashMap<>();
    /** Running usage {bytes, files} of the directories that have a quota. Guarded by {@code this}. */
    private final Map<VfsPath, long[]> usage = new HashMap<>();
    /**
     * Operations that write blobs (file writes, repair, re-tiering) hold the read side while they store data and
     * commit it; garbage collection takes the write side, so it never sees blobs that are not referenced yet. The
     * service monitor is only ever held for short commits, never across engine I/O. Order: gate, then monitor.
     */
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();

    public DefaultFileSystemService(VirtualFileSystem vfs, VolumeManager volumeManager, ObjectStore objectStore) {
        this(vfs, volumeManager, objectStore, null);
    }

    public DefaultFileSystemService(VirtualFileSystem vfs, VolumeManager volumeManager, ObjectStore objectStore, ReplicaPlanner replicaPlanner) {
        this(vfs, volumeManager, objectStore, replicaPlanner, NamespaceJournal.none());
    }

    /**
     * @param journal durable log of namespace changes. Existing records are replayed here, so the
     *                namespace survives restarts. Use {@link NamespaceJournal#none()} for a volatile namespace.
     */
    public DefaultFileSystemService(VirtualFileSystem vfs, VolumeManager volumeManager, ObjectStore objectStore,
                                    ReplicaPlanner replicaPlanner, NamespaceJournal journal) {
        this(vfs, volumeManager, objectStore, replicaPlanner, journal, p -> true);
    }

    /**
     * @param persistFilter decides, per path, whether namespace changes are written to the journal. Use
     *                      {@link #durableVolumesOnly} so that entries of volatile volumes (whose data is gone after a
     *                      restart) never leave ghost files behind.
     */
    public DefaultFileSystemService(VirtualFileSystem vfs, VolumeManager volumeManager, ObjectStore objectStore,
                                    ReplicaPlanner replicaPlanner, NamespaceJournal journal,
                                    Predicate<VfsPath> persistFilter) {
        this(vfs, volumeManager, objectStore, replicaPlanner, journal, persistFilter, null);
    }

    /**
     * @param backend stores the files of {@code pool} mounts through the data engine; requires a journal that
     *                {@linkplain NamespaceJournal#protectsSecrets() protects secrets}, because the journal then
     *                holds the file keys
     */
    public DefaultFileSystemService(VirtualFileSystem vfs, VolumeManager volumeManager, ObjectStore objectStore,
                                    ReplicaPlanner replicaPlanner, NamespaceJournal journal,
                                    Predicate<VfsPath> persistFilter, DataBackend backend) {
        if (backend != null && !journal.protectsSecrets()) {
            throw new IllegalArgumentException("a data engine backend needs an encrypted namespace journal");
        }
        this.backend = backend;
        this.persistFilter = Objects.requireNonNull(persistFilter, "persistFilter");
        this.vfs = Objects.requireNonNull(vfs, "vfs");
        this.volumeManager = Objects.requireNonNull(volumeManager, "volumeManager");
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
        this.replicaPlanner = replicaPlanner;
        this.journal = Objects.requireNonNull(journal, "journal");
        recover();
    }

    // ── durability ─────────────────────────────────────────────────────────────

    /**
     * Persist only what lives on volumes whose data survives a restart (LOCAL and REMOTE). Paths that are not
     * covered by a mount, or whose volume is not attached right now (e.g. an unplugged disk), are kept so
     * that no names are lost while a volume is temporarily away.
     */
    public static Predicate<VfsPath> durableVolumesOnly(VirtualFileSystem vfs, VolumeManager volumes) {
        Objects.requireNonNull(vfs, "vfs");
        Objects.requireNonNull(volumes, "volumes");
        return path -> {
            Optional<Mount> mount = vfs.findMount(path);
            if (mount.isEmpty()) {
                return true;
            }
            Optional<Volume> volume = volumes.get(mount.get().volumeId());
            if (volume.isEmpty()) {
                return true;
            }
            VolumeKind kind = volume.get().kind();
            return kind == VolumeKind.LOCAL || kind == VolumeKind.REMOTE || kind == VolumeKind.POOL
                    || kind == VolumeKind.EXTERNAL;
        };
    }

    private void log(VfsPath path, NamespaceRecord record) {
        if (persistFilter.test(path)) {
            journal.append(record);
        }
    }

    private synchronized void recover() {
        AtomicLong maxId = new AtomicLong();
        journal.replay(record -> apply(record, maxId));
        inodeSeq.set(maxId.get() + 1);
        rebuildUsage();

        long live = inodes.size() + policies.size() + attrs.size()
                + inodes.values().stream().filter(Inode::hasContent).count();
        if (journal.recordCount() > COMPACT_MIN_RECORDS && journal.recordCount() > 2 * live) {
            compact();
        }
    }

    private void apply(NamespaceRecord record, AtomicLong maxId) {
        switch (record) {
            case NamespaceRecord.Create c -> {
                VfsPath p = VfsPath.parse(c.path());
                boolean hasParent = c.parentId() != NamespaceRecord.NO_PARENT;
                inodes.put(p, new Inode(new InodeId(c.inodeId()), c.type(), p,
                        hasParent ? new InodeId(c.parentId()) : null, null));
                if (hasParent && !p.isRoot()) {
                    children.computeIfAbsent(p.parent(), k -> ConcurrentHashMap.newKeySet()).add(p.name());
                }
                maxId.accumulateAndGet(c.inodeId(), Math::max);
            }
            case NamespaceRecord.SetFile f -> {
                Inode inode = inodes.get(VfsPath.parse(f.path()));
                if (inode != null) {
                    inode.setFile(FileRef.parse(f.fileRef()), f.policy());
                    inode.setSize(f.size());
                }
            }
            case NamespaceRecord.SetPolicy sp -> {
                VfsPath p = VfsPath.parse(sp.path());
                if (sp.policy().isEmpty()) {
                    policies.remove(p);
                } else {
                    policies.put(p, sp.policy());
                }
            }
            case NamespaceRecord.SetAttr a -> storeAttr(VfsPath.parse(a.path()), a.key(), a.value());
            case NamespaceRecord.Unlink u -> {
                VfsPath p = VfsPath.parse(u.path());
                policies.remove(p);
                attrs.remove(p);
                inodes.remove(p);
                children.remove(p);
                if (!p.isRoot()) {
                    Set<String> siblings = children.get(p.parent());
                    if (siblings != null) {
                        siblings.remove(p.name());
                    }
                }
            }
            case NamespaceRecord.SetData s -> {
                Inode inode = inodes.get(VfsPath.parse(s.path()));
                if (inode != null) {
                    inode.setData(new ObjectId(s.objectIdHex()));
                    inode.setSize(s.size());
                }
            }
        }
    }

    /** Rewrites the journal so it contains only the live namespace (drops history of deleted/overwritten entries). */
    public synchronized void compact() {
        journal.rewrite(snapshot());
    }

    /** The live namespace as the records that rebuild it (what a compaction writes). */
    public synchronized List<NamespaceRecord> exportSnapshot() {
        return snapshot();
    }

    private List<NamespaceRecord> snapshot() {
        List<Map.Entry<VfsPath, Inode>> entries = new ArrayList<>(inodes.entrySet());
        // parents before children so that replay order is always valid
        entries.sort(Comparator.<Map.Entry<VfsPath, Inode>>comparingInt(e -> e.getKey().segments().size())
                .thenComparing(e -> e.getKey().toString()));
        List<NamespaceRecord> out = new ArrayList<>(entries.size() * 2);
        for (Map.Entry<VfsPath, Inode> e : entries) {
            if (!persistFilter.test(e.getKey())) {
                continue;
            }
            Inode inode = e.getValue();
            long parent = inode.parentId() == null ? NamespaceRecord.NO_PARENT : inode.parentId().value();
            out.add(new NamespaceRecord.Create(e.getKey().toString(), inode.type(), inode.id().value(), parent));
            if (inode.data() != null) {
                out.add(new NamespaceRecord.SetData(e.getKey().toString(), inode.data().hex(), inode.size()));
            }
            if (inode.ref() != null) {
                out.add(new NamespaceRecord.SetFile(e.getKey().toString(), inode.ref().encode(), inode.storageClass(),
                        inode.size()));
            }
        }
        for (Map.Entry<VfsPath, String> e : policies.entrySet()) {
            if (persistFilter.test(e.getKey())) {
                out.add(new NamespaceRecord.SetPolicy(e.getKey().toString(), e.getValue()));
            }
        }
        for (Map.Entry<VfsPath, Map<String, String>> e : attrs.entrySet()) {
            if (persistFilter.test(e.getKey())) {
                for (Map.Entry<String, String> a : e.getValue().entrySet()) {
                    out.add(new NamespaceRecord.SetAttr(e.getKey().toString(), a.getKey(), a.getValue()));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized void close() {
        journal.close();
    }

    /** True for the top-level synthetic directories ({@code /mnt}, {@code /vol}, {@code /repo}) themselves. */
    private static boolean isSyntheticRoot(VfsPath path) {
        if (path.isRoot()) {
            return false;
        }
        List<String> segs = path.segments();
        return segs.size() == 1 && DefaultVirtualFileSystem.ROOT_SYNTHETIC.contains(segs.get(0));
    }

    private void checkAllowedNamespace(VfsPath path) {
        if (path.isRoot()) {
            throw new FsException(FsErrorCode.DENIED, "Operation not allowed on root path");
        }
        List<String> segs = path.segments();
        String top = segs.get(0);
        if (DefaultVirtualFileSystem.VOL.equals(top) || DefaultVirtualFileSystem.REPO.equals(top)) {
            throw new FsException(FsErrorCode.DENIED, "Modifying synthetic namespace '/" + top + "' is denied");
        }
        if (!DefaultVirtualFileSystem.MNT.equals(top)) {
            throw new FsException(FsErrorCode.DENIED, "Creating outside '/mnt' is denied");
        }
        if (segs.size() == 1) {
            throw new FsException(FsErrorCode.DENIED, "Cannot modify '/mnt' directly");
        }
    }

    private Volume checkVolumeAvailable(VfsPath path) {
        Optional<Mount> mountOpt = vfs.findMount(path);
        if (mountOpt.isEmpty()) {
            throw new FsException(FsErrorCode.NOT_FOUND, "No mount covers path: " + path);
        }
        Mount mount = mountOpt.get();
        Optional<Volume> volOpt = volumeManager.get(mount.volumeId());
        if (volOpt.isEmpty()) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "Volume is unavailable: " + mount.volumeId());
        }
        Volume vol = volOpt.get();
        if (vol.state() != VolumeState.ONLINE && vol.state() != VolumeState.DEGRADED) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "Volume is not online: " + vol.id());
        }
        return vol;
    }

    private boolean isMountPoint(VfsPath path) {
        Optional<Mount> mountOpt = vfs.findMount(path);
        return mountOpt.isPresent() && mountOpt.get().mountPoint().equals(path);
    }

    private Inode getOrCreateMountPointInode(VfsPath mountPoint) {
        return inodes.computeIfAbsent(mountPoint, p -> {
            InodeId id = new InodeId(inodeSeq.getAndIncrement());
            log(p, new NamespaceRecord.Create(p.toString(), NodeType.DIRECTORY, id.value(), NamespaceRecord.NO_PARENT));
            return new Inode(id, NodeType.DIRECTORY, p, null, null);
        });
    }

    private Inode resolveParentDirectory(VfsPath parent) {
        if (isMountPoint(parent)) {
            return getOrCreateMountPointInode(parent);
        }
        Inode parentInode = inodes.get(parent);
        if (parentInode == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Parent directory does not exist: " + parent);
        }
        if (parentInode.type() != NodeType.DIRECTORY) {
            throw new FsException(FsErrorCode.NOT_A_DIRECTORY, "Parent is not a directory: " + parent);
        }
        return parentInode;
    }

    @Override
    public synchronized void mkdir(VfsPath path) throws FsException {
        Objects.requireNonNull(path, "path");
        checkAllowedNamespace(path);
        checkVolumeAvailable(path);

        if (inodes.containsKey(path) || isMountPoint(path)) {
            throw new FsException(FsErrorCode.ALREADY_EXISTS, "Path already exists: " + path);
        }

        VfsPath parent = path.parent();
        Inode parentInode = resolveParentDirectory(parent);
        requireWritable(parent, path);

        InodeId id = new InodeId(inodeSeq.getAndIncrement());
        Inode inode = new Inode(id, NodeType.DIRECTORY, path, parentInode.id(), null);
        log(path, new NamespaceRecord.Create(path.toString(), NodeType.DIRECTORY, id.value(), parentInode.id().value()));
        inodes.put(path, inode);
        children.computeIfAbsent(parent, p -> ConcurrentHashMap.newKeySet()).add(path.name());
    }

    @Override
    public synchronized void createFile(VfsPath path) throws FsException {
        Objects.requireNonNull(path, "path");
        checkAllowedNamespace(path);
        checkVolumeAvailable(path);

        if (inodes.containsKey(path) || isMountPoint(path)) {
            throw new FsException(FsErrorCode.ALREADY_EXISTS, "Path already exists: " + path);
        }

        VfsPath parent = path.parent();
        Inode parentInode = resolveParentDirectory(parent);
        requireWritable(parent, path);
        checkQuota(parent, path, 0, 1);

        InodeId id = new InodeId(inodeSeq.getAndIncrement());
        Inode inode = new Inode(id, NodeType.FILE, path, parentInode.id(), null);
        log(path, new NamespaceRecord.Create(path.toString(), NodeType.FILE, id.value(), parentInode.id().value()));
        adjustUsage(parent, 0, 1);
        inodes.put(path, inode);
        children.computeIfAbsent(parent, p -> ConcurrentHashMap.newKeySet()).add(path.name());
    }

    @Override
    public void unlink(VfsPath path) throws FsException {
        FileRef dropped;
        synchronized (this) {
            dropped = unlinkLocked(path);
        }
        if (dropped != null && backend != null) {
            dropQuietly(dropped); // engine I/O happens outside the namespace lock
            backend.tracker().forget(path.toString());
        }
    }

    private FileRef unlinkLocked(VfsPath path) {
        Objects.requireNonNull(path, "path");
        checkAllowedNamespace(path);
        checkVolumeAvailable(path);

        if (isMountPoint(path)) {
            throw new FsException(FsErrorCode.DENIED, "Cannot unlink mount point: " + path);
        }

        Inode inode = inodes.get(path);
        if (inode == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Path not found: " + path);
        }

        if (inode.type() == NodeType.DIRECTORY) {
            Set<String> sub = children.get(path);
            if (sub != null && !sub.isEmpty()) {
                throw new FsException(FsErrorCode.CONFLICT, "Directory is not empty: " + path);
            }
        }

        VfsPath parent = path.parent();
        requireWritable(parent, path);
        if (isTrue(effective(parent, NamespaceAdmin.DENY_DELETE))) {
            throw new FsException(FsErrorCode.READ_ONLY, "Deleting is denied under " + definedAt(parent,
                    NamespaceAdmin.DENY_DELETE) + ": " + path);
        }

        log(path, new NamespaceRecord.Unlink(path.toString()));
        FileRef dropped = inode.ref();
        if (inode.type() == NodeType.FILE) {
            adjustUsage(parent, -Math.max(inode.size(), 0), -1);
        }
        inodes.remove(path);
        policies.remove(path);
        attrs.remove(path);
        usage.remove(path);
        children.remove(path);
        Set<String> parentChildren = children.get(parent);
        if (parentChildren != null) {
            parentChildren.remove(path.name());
        }
        return dropped;
    }

    @Override
    public DirectoryEntry stat(VfsPath path) throws FsException {
        Objects.requireNonNull(path, "path");
        if (path.isRoot()) {
            return new DirectoryEntry("", NodeType.DIRECTORY);
        }
        if (isSyntheticRoot(path)) {
            return new DirectoryEntry(path.name(), NodeType.DIRECTORY);
        }

        checkVolumeAvailable(path);

        if (isMountPoint(path)) {
            return new DirectoryEntry(path.name(), NodeType.DIRECTORY);
        }

        Inode inode = inodes.get(path);
        if (inode == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Path not found: " + path);
        }

        return new DirectoryEntry(path.name(), inode.type());
    }

    @Override
    public List<DirectoryEntry> readdir(VfsPath path) throws FsException {
        Objects.requireNonNull(path, "path");
        if (path.isRoot() || isSyntheticRoot(path)) {
            return vfs.list(path);
        }

        checkVolumeAvailable(path);

        boolean isMount = isMountPoint(path);
        Inode inode = inodes.get(path);
        if (!isMount) {
            if (inode == null) {
                throw new FsException(FsErrorCode.NOT_FOUND, "Directory not found: " + path);
            }
            if (inode.type() != NodeType.DIRECTORY) {
                throw new FsException(FsErrorCode.NOT_A_DIRECTORY, "Path is not a directory: " + path);
            }
        }

        Set<String> childNames = children.get(path);
        if (childNames == null || childNames.isEmpty()) {
            return Collections.emptyList();
        }

        List<DirectoryEntry> entries = new ArrayList<>();
        for (String childName : childNames) {
            VfsPath childPath = path.resolve(childName);
            Inode childInode = inodes.get(childPath);
            if (childInode != null) {
                entries.add(new DirectoryEntry(childName, childInode.type()));
            }
        }
        return entries;
    }

    /** What a write needs to know, decided under the lock before the data is stored. */
    private record WriteTarget(Inode inode, Volume volume, boolean pool, String policyName) {
    }

    /** Where a write ended up, ready to be committed to the namespace. */
    private record Stored(ObjectId blob, FileRef ref, String storedAs) {
    }

    @Override
    public ObjectId writeFully(VfsPath path, byte[] data) throws FsException {
        return write(path, data, null);
    }

    @Override
    public synchronized String version(VfsPath path) throws FsException {
        Objects.requireNonNull(path, "path");
        checkAllowedNamespace(path);
        Inode inode = inodes.get(path);
        if (inode == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "File does not exist: " + path);
        }
        return versionOf(inode);
    }

    /** Content identity: the pool file's root id, or the blob id; stable across restarts, new on every pool write. */
    private static String versionOf(Inode inode) {
        if (inode.ref() != null) return "r:" + inode.ref().rootId().hex();
        if (inode.data() != null) return "b:" + inode.data().hex();
        return "";
    }

    @Override
    public ObjectId writeIfVersion(VfsPath path, String expectedVersion, byte[] data) throws FsException {
        return write(path, data, Objects.requireNonNull(expectedVersion, "expectedVersion"));
    }

    private ObjectId write(VfsPath path, byte[] data, String expectedVersion) throws FsException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(data, "data");
        WriteTarget target;
        synchronized (this) {
            target = prepareWrite(path, data.length);
            if (expectedVersion != null && !expectedVersion.equals(versionOf(target.inode()))) {
                throw new FsException(FsErrorCode.CONFLICT, "File changed since version " + expectedVersion + ": " + path);
            }
        }
        if (target.pool()) {
            requireBackend();
            gate.readLock().lock();
        }
        try {
            // the slow part (erasure coding, network) runs without the namespace lock
            Stored stored = store(path, target, data);
            FileRef replaced;
            try {
                synchronized (this) {
                    if (expectedVersion != null && inodes.get(path) == target.inode()
                            && !expectedVersion.equals(versionOf(target.inode()))) {
                        throw new FsException(FsErrorCode.CONFLICT, "File changed while it was being written: " + path);
                    }
                    replaced = commitWrite(path, target, stored, data.length);
                }
            } catch (RuntimeException e) {
                if (stored.ref() != null) {
                    dropQuietly(stored.ref());
                }
                throw e;
            }
            if (target.pool()) {
                backend.tracker().record(path.toString());
            }
            if (replaced != null) {
                dropQuietly(replaced);
            }
            return stored.ref() != null ? stored.ref().rootId() : stored.blob();
        } finally {
            if (target.pool()) {
                gate.readLock().unlock();
            }
        }
    }

    private WriteTarget prepareWrite(VfsPath path, int length) {
        checkAllowedNamespace(path);
        Volume volume = checkVolumeAvailable(path);

        if (isMountPoint(path)) {
            throw new FsException(FsErrorCode.IS_DIRECTORY, "Path is a mount point directory: " + path);
        }

        Inode inode = inodes.get(path);
        if (inode == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "File does not exist: " + path);
        }
        if (inode.type() == NodeType.DIRECTORY) {
            throw new FsException(FsErrorCode.IS_DIRECTORY, "Path is a directory: " + path);
        }
        checkWriteRules(path, inode, length);
        boolean pool = volume.kind() == VolumeKind.POOL;
        String policyName = pool ? effectivePolicy(path.parent(), path, volume) : "";
        return new WriteTarget(inode, volume, pool, policyName);
    }

    private Stored store(VfsPath path, WriteTarget t, byte[] data) {
        if (t.pool()) {
            FileRef ref = backend.engine().put(data, policyFor(t.policyName()));
            return new Stored(null, ref, backend.catalog().writeName(t.policyName()));
        }
        String groupStr = t.volume().metadata().getString("replica-group", "");
        ObjectId objectId;
        if (replicaPlanner != null && !groupStr.isBlank()) {
            ReplicaGroupId groupId = new ReplicaGroupId(UUID.fromString(groupStr));
            objectId = replicaPlanner.put(groupId, data).id();
        } else {
            objectId = t.volume().store().put(data).id();
        }
        return new Stored(objectId, null, "");
    }

    /** Publishes stored data under the lock; returns the engine file it replaced (to be deleted by the caller). */
    private FileRef commitWrite(VfsPath path, WriteTarget t, Stored stored, int length) {
        Inode inode = inodes.get(path);
        if (inode != t.inode()) {
            throw new FsException(FsErrorCode.CONFLICT, "File was removed while it was being written: " + path);
        }
        checkWriteRules(path, inode, length); // rules and usage may have moved while the data was being stored
        VfsPath parent = path.parent();
        long delta = length - Math.max(inode.size(), 0);
        FileRef old = inode.ref();
        if (stored.ref() != null) {
            log(path, new NamespaceRecord.SetFile(path.toString(), stored.ref().encode(), stored.storedAs(), length));
            inode.setFile(stored.ref(), stored.storedAs());
            inode.setData(null);
        } else {
            log(path, new NamespaceRecord.SetData(path.toString(), stored.blob().hex(), length));
            inode.setData(stored.blob());
        }
        inode.setSize(length);
        adjustUsage(parent, delta, 0);
        return old;
    }

    @Override
    public boolean hasContent(VfsPath path) throws FsException {
        Objects.requireNonNull(path, "path");
        checkAllowedNamespace(path);
        Inode inode = inodes.get(path);
        if (inode == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "File does not exist: " + path);
        }
        return inode.hasContent();
    }

    @Override
    public byte[] readFully(VfsPath path) throws FsException {
        Objects.requireNonNull(path, "path");
        checkAllowedNamespace(path);
        Volume volume = checkVolumeAvailable(path);

        if (isMountPoint(path)) {
            throw new FsException(FsErrorCode.IS_DIRECTORY, "Path is a mount point directory: " + path);
        }

        Inode inode = inodes.get(path);
        if (inode == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "File does not exist: " + path);
        }
        if (inode.type() == NodeType.DIRECTORY) {
            throw new FsException(FsErrorCode.IS_DIRECTORY, "Path is a directory: " + path);
        }

        if (volume.kind() == VolumeKind.POOL) {
            FileRef ref = inode.ref();
            if (ref == null) {
                return new byte[0];
            }
            requireBackend();
            backend.tracker().record(path.toString());
            return backend.engine().getAll(ref);
        }

        ObjectId dataId = inode.data();
        if (dataId == null) {
            return new byte[0];
        }

        String groupStr = volume.metadata().getString("replica-group", "");
        if (replicaPlanner != null && !groupStr.isBlank()) {
            ReplicaGroupId groupId = new ReplicaGroupId(UUID.fromString(groupStr));
            return replicaPlanner.get(groupId, dataId);
        }

        return volume.store().get(dataId);
    }

    // ── pool mounts (data engine) ──────────────────────────────────────────────

    private void requireBackend() {
        if (backend == null) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE,
                    "pool mounts need the data engine, which requires an unlocked keyring");
        }
    }

    private StoragePolicy policyFor(String name) {
        try {
            return backend.catalog().forWrite(name);
        } catch (IllegalArgumentException e) {
            throw new FsException(FsErrorCode.NOT_FOUND, e.getMessage());
        }
    }

    /** Removes what a replaced or deleted file left in the engine; failures only leave orphans for the garbage collector. */
    private void dropQuietly(FileRef ref) {
        try {
            backend.engine().delete(ref);
        } catch (RuntimeException e) {
            // unreadable manifest or unreachable nodes: the blobs are orphaned until garbage collection
        }
    }

    /** Walks from {@code start} up to the mount point looking for a directory policy, then the volume's, then the default. */
    private String effectivePolicy(VfsPath start, VfsPath forPath, Volume volume) {
        Optional<Mount> mount = vfs.findMount(forPath);
        VfsPath dir = start;
        while (true) {
            String named = policies.get(dir);
            if (named != null && !named.isEmpty()) {
                return named;
            }
            if (dir.isRoot() || (mount.isPresent() && dir.equals(mount.get().mountPoint()))) {
                break;
            }
            dir = dir.parent();
        }
        String fromVolume = volume.metadata().getString("policy", "");
        return fromVolume.isBlank() ? com.redactado.noray.data.tier.PolicyCatalog.DEFAULT_POLICY : fromVolume;
    }

    private Volume poolVolumeOf(VfsPath path) {
        requireBackend();
        Volume volume = checkVolumeAvailable(path);
        if (volume.kind() != VolumeKind.POOL) {
            throw new FsException(FsErrorCode.CONFLICT, "storage policies only apply to pool mounts: " + path);
        }
        return volume;
    }

    @Override
    public synchronized void setPolicy(VfsPath dir, String policy) throws FsException {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(policy, "policy");
        checkAllowedNamespace(dir);
        poolVolumeOf(dir);
        if (!isMountPoint(dir)) {
            Inode inode = inodes.get(dir);
            if (inode == null) {
                throw new FsException(FsErrorCode.NOT_FOUND, "Directory not found: " + dir);
            }
            if (inode.type() != NodeType.DIRECTORY) {
                throw new FsException(FsErrorCode.NOT_A_DIRECTORY, "Not a directory: " + dir);
            }
        } else {
            getOrCreateMountPointInode(dir);
        }
        if (!policy.isEmpty() && !backend.catalog().known(policy)) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Unknown storage policy: " + policy);
        }
        log(dir, new NamespaceRecord.SetPolicy(dir.toString(), policy));
        if (policy.isEmpty()) {
            policies.remove(dir);
        } else {
            policies.put(dir, policy);
        }
    }

    @Override
    public String policyOf(VfsPath path) throws FsException {
        Objects.requireNonNull(path, "path");
        checkAllowedNamespace(path);
        Volume volume = poolVolumeOf(path);
        Inode inode = inodes.get(path);
        boolean isDir = isMountPoint(path) || (inode != null && inode.type() == NodeType.DIRECTORY);
        return effectivePolicy(isDir ? path : path.parent(), path, volume);
    }

    @Override
    public int applyPolicy(VfsPath dir) throws FsException {
        Objects.requireNonNull(dir, "dir");
        checkAllowedNamespace(dir);
        Volume volume;
        List<Inode> candidates = new ArrayList<>();
        Map<Inode, String> targets = new LinkedHashMap<>();
        synchronized (this) {
            volume = poolVolumeOf(dir);
            for (Inode inode : new ArrayList<>(inodes.values())) {
                if (inode.type() != NodeType.FILE || inode.ref() == null || !isUnder(inode.path(), dir)) {
                    continue;
                }
                String name = effectivePolicy(inode.path().parent(), inode.path(), volume);
                String target = backend.catalog().auto(name).isPresent()
                        ? (backend.catalog().policy(inode.storageClass()).isPresent() ? inode.storageClass() : backend.catalog().writeName(name))
                        : name;
                if (!target.equals(inode.storageClass())) {
                    targets.put(inode, target);
                }
            }
        }
        int changed = 0;
        for (Map.Entry<Inode, String> e : targets.entrySet()) {
            if (retierFile(e.getKey(), e.getValue())) {
                changed++;
            }
        }
        return changed;
    }

    @Override
    public String storageClassOf(VfsPath file) throws FsException {
        Objects.requireNonNull(file, "file");
        poolVolumeOf(file);
        Inode inode = inodes.get(file);
        if (inode == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "File does not exist: " + file);
        }
        return inode.storageClass();
    }

    @Override
    public void observeNodes() {
        requireBackend();
        backend.watch().observe(backend.cluster());
    }

    /** Snapshot of the files stored by the engine. */
    private synchronized List<Inode> engineFiles() {
        List<Inode> out = new ArrayList<>();
        for (Inode inode : inodes.values()) {
            if (inode.type() == NodeType.FILE && inode.ref() != null) {
                out.add(inode);
            }
        }
        out.sort(Comparator.comparing(i -> i.path().toString()));
        return out;
    }

    @Override
    public RepairSummary repair(boolean scrub, boolean unavailableIsLost) throws FsException {
        requireBackend();
        Predicate<String> lost = unavailableIsLost
                ? id -> backend.cluster().find(id).map(n -> !n.available()).orElse(true)
                : id -> backend.watch().isLost(id, backend.cluster());
        int files = 0, repaired = 0, failed = 0, pieces = 0, recreated = 0, pages = 0, chunksLost = 0;
        for (Inode inode : engineFiles()) {
            files++;
            gate.readLock().lock();
            try {
                FileRef before = inode.ref();
                if (before == null) {
                    continue; // deleted since the snapshot
                }
                com.redactado.noray.data.RepairReport r = backend.engine().repair(before, scrub, lost);
                pieces += r.piecesRebuilt();
                recreated += r.variantsRecreated();
                pages += r.pagesRestored();
                chunksLost += r.chunksLost();
                if (r.changed()) {
                    boolean committed;
                    synchronized (this) {
                        // only if the file was not rewritten meanwhile: otherwise the repaired copy is already stale
                        committed = inodes.get(inode.path()) == inode && inode.ref() == before;
                        if (committed) {
                            log(inode.path(), new NamespaceRecord.SetFile(inode.path().toString(), r.ref().encode(),
                                    inode.storageClass(), inode.size()));
                            inode.setFile(r.ref(), inode.storageClass());
                        }
                    }
                    try {
                        backend.engine().deleteManifest(committed ? before : r.ref());
                    } catch (RuntimeException e) {
                        // orphaned pages: garbage collection will take them
                    }
                }
                if (r.changed() || r.pagesRestored() > 0) {
                    repaired++;
                }
            } catch (RuntimeException e) {
                failed++;
            } finally {
                gate.readLock().unlock();
            }
        }
        return new RepairSummary(files, repaired, failed, pieces, recreated, pages, chunksLost);
    }

    @Override
    public com.redactado.noray.data.GcReport gc(boolean dryRun) throws FsException {
        requireBackend();
        gate.writeLock().lock(); // no write is half-way while the live set is established
        try {
            Set<ObjectId> live = new HashSet<>();
            for (Inode inode : engineFiles()) {
                FileRef ref = inode.ref();
                if (ref == null) {
                    continue;
                }
                try {
                    live.addAll(backend.engine().liveBlobs(ref));
                } catch (RuntimeException e) {
                    throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "garbage collection aborted: cannot read the "
                            + "manifest of " + inode.path() + ", so the set of needed blobs is unknown", e);
                }
            }
            return backend.engine().collectGarbage(live, backend.gcGrace(), dryRun);
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override
    public TierSummary autoTier() throws FsException {
        requireBackend();
        var catalog = backend.catalog();
        var tracker = backend.tracker();
        int evaluated = 0, demoted = 0, promoted = 0, failed = 0;
        for (Inode inode : engineFiles()) {
            try {
                Optional<Mount> mount = vfs.findMount(inode.path());
                Optional<Volume> volume = mount.flatMap(m -> volumeManager.get(m.volumeId()));
                if (volume.isEmpty() || volume.get().kind() != VolumeKind.POOL
                        || (volume.get().state() != VolumeState.ONLINE && volume.get().state() != VolumeState.DEGRADED)) {
                    continue;
                }
                String name = effectivePolicy(inode.path().parent(), inode.path(), volume.get());
                var profile = catalog.auto(name);
                if (profile.isEmpty()) {
                    continue;
                }
                evaluated++;
                var a = profile.get();
                String key = inode.path().toString();
                double rate = tracker.ratePerDay(key);
                long idleLimit = a.demoteAfterIdle().toMillis();
                String current = inode.storageClass();
                if (current.equals(a.hot())) {
                    if (tracker.observedMillis() >= idleLimit && tracker.idleMillis(key) >= idleLimit
                            && rate < a.demoteBelowPerDay()) {
                        if (retierFile(inode, a.cold())) {
                            demoted++;
                        }
                    }
                } else if (current.equals(a.cold())) {
                    if (rate > a.promoteAbovePerDay()) {
                        if (retierFile(inode, a.hot())) {
                            promoted++;
                        }
                    }
                }
            } catch (RuntimeException e) {
                failed++;
            }
        }
        return new TierSummary(evaluated, demoted, promoted, failed);
    }

    private static boolean isUnder(VfsPath path, VfsPath dir) {
        VfsPath p = path;
        while (!p.isRoot()) {
            if (p.equals(dir)) {
                return true;
            }
            p = p.parent();
        }
        return dir.isRoot();
    }

    /**
     * Re-stores one file with the named plain policy and records the new manifest. The engine work runs outside the
     * namespace lock; the result is only published if the file was not rewritten meanwhile.
     */
    private boolean retierFile(Inode inode, String plainPolicy) {
        StoragePolicy policy = backend.catalog().policy(plainPolicy)
                .orElseThrow(() -> new FsException(FsErrorCode.NOT_FOUND, "Unknown storage policy: " + plainPolicy));
        gate.readLock().lock();
        try {
            FileRef old = inode.ref();
            if (old == null) {
                return false;
            }
            FileRef updated = backend.engine().retier(old, policy);
            boolean committed;
            synchronized (this) {
                committed = inodes.get(inode.path()) == inode && inode.ref() == old;
                if (committed) {
                    log(inode.path(), new NamespaceRecord.SetFile(inode.path().toString(), updated.encode(), plainPolicy,
                            inode.size()));
                    inode.setFile(updated, plainPolicy);
                }
            }
            try {
                backend.engine().deleteManifest(committed ? old : updated);
            } catch (RuntimeException e) {
                // stray manifest pages stay behind as orphans until garbage collection
            }
            return committed;
        } finally {
            gate.readLock().unlock();
        }
    }

    // ── quotas and permissions ─────────────────────────────────────────────────

    private static boolean isTrue(String value) {
        return "true".equals(value);
    }

    /** Value of {@code key} at the nearest directory (starting at {@code dir}, going up) that defines it. */
    private String effective(VfsPath dir, String key) {
        VfsPath d = dir;
        while (true) {
            Map<String, String> m = attrs.get(d);
            if (m != null && m.containsKey(key)) {
                return m.get(key);
            }
            if (d.isRoot()) {
                return null;
            }
            d = d.parent();
        }
    }

    private VfsPath definedAt(VfsPath dir, String key) {
        VfsPath d = dir;
        while (!d.isRoot()) {
            Map<String, String> m = attrs.get(d);
            if (m != null && m.containsKey(key)) {
                return d;
            }
            d = d.parent();
        }
        return d;
    }

    private void requireWritable(VfsPath parent, VfsPath target) {
        if (isTrue(effective(parent, NamespaceAdmin.READONLY))) {
            throw new FsException(FsErrorCode.READ_ONLY, "Read-only subtree (set on "
                    + definedAt(parent, NamespaceAdmin.READONLY) + "): " + target);
        }
    }

    /** Everything a write of {@code length} bytes to {@code path} has to satisfy. Caller holds the lock. */
    private void checkWriteRules(VfsPath path, Inode inode, long length) {
        VfsPath parent = path.parent();
        requireWritable(parent, path);
        if (inode.hasContent() && isTrue(effective(parent, NamespaceAdmin.DENY_OVERWRITE))) {
            throw new FsException(FsErrorCode.READ_ONLY, "Existing files cannot be replaced under "
                    + definedAt(parent, NamespaceAdmin.DENY_OVERWRITE) + ": " + path);
        }
        String max = effective(parent, NamespaceAdmin.MAX_FILE_BYTES);
        if (max != null && length > Long.parseLong(max)) {
            throw new FsException(FsErrorCode.QUOTA_EXCEEDED, "File of " + length + " bytes exceeds the limit of "
                    + max + " set on " + definedAt(parent, NamespaceAdmin.MAX_FILE_BYTES) + ": " + path);
        }
        checkQuota(parent, path, length - Math.max(inode.size(), 0), 0);
    }

    /** Fails if growing every quota-limited ancestor by the given amounts would exceed it. */
    private void checkQuota(VfsPath from, VfsPath target, long dBytes, long dFiles) {
        if (dBytes <= 0 && dFiles <= 0) {
            return;
        }
        VfsPath d = from;
        while (true) {
            long[] u = usage.get(d);
            if (u != null) {
                Map<String, String> m = attrs.get(d);
                String qb = m == null ? null : m.get(NamespaceAdmin.QUOTA_BYTES);
                String qf = m == null ? null : m.get(NamespaceAdmin.QUOTA_FILES);
                if (qb != null && dBytes > 0 && u[0] + dBytes > Long.parseLong(qb)) {
                    throw new FsException(FsErrorCode.QUOTA_EXCEEDED, "Byte quota of " + d + " exceeded (" + u[0] + " + "
                            + dBytes + " > " + qb + "): " + target);
                }
                if (qf != null && dFiles > 0 && u[1] + dFiles > Long.parseLong(qf)) {
                    throw new FsException(FsErrorCode.QUOTA_EXCEEDED, "File quota of " + d + " exceeded (" + u[1] + " + "
                            + dFiles + " > " + qf + "): " + target);
                }
            }
            if (d.isRoot()) {
                return;
            }
            d = d.parent();
        }
    }

    private void adjustUsage(VfsPath from, long dBytes, long dFiles) {
        if (usage.isEmpty()) {
            return;
        }
        VfsPath d = from;
        while (true) {
            long[] u = usage.get(d);
            if (u != null) {
                u[0] = Math.max(0, u[0] + dBytes);
                u[1] = Math.max(0, u[1] + dFiles);
            }
            if (d.isRoot()) {
                return;
            }
            d = d.parent();
        }
    }

    private long[] scanUsage(VfsPath dir) {
        long bytes = 0;
        long files = 0;
        for (Inode inode : inodes.values()) {
            if (inode.type() == NodeType.FILE && isUnder(inode.path(), dir)) {
                bytes += Math.max(inode.size(), 0);
                files++;
            }
        }
        return new long[]{bytes, files};
    }

    private void rebuildUsage() {
        usage.clear();
        usage.putAll(computeUsage());
    }

    /** Usage of every quota-limited directory in one pass over the inode table (each file charges its ancestors). */
    private Map<VfsPath, long[]> computeUsage() {
        Map<VfsPath, long[]> fresh = new HashMap<>();
        for (Map.Entry<VfsPath, Map<String, String>> e : attrs.entrySet()) {
            if (hasQuota(e.getValue())) {
                fresh.put(e.getKey(), new long[2]);
            }
        }
        if (fresh.isEmpty()) {
            return fresh;
        }
        for (Inode inode : inodes.values()) {
            if (inode.type() != NodeType.FILE) {
                continue;
            }
            VfsPath d = inode.path();
            while (!d.isRoot()) {
                d = d.parent();
                long[] u = fresh.get(d);
                if (u != null) {
                    u[0] += Math.max(inode.size(), 0);
                    u[1]++;
                }
            }
        }
        return fresh;
    }

    @Override
    public synchronized int reconcileUsage() {
        Map<VfsPath, long[]> fresh = computeUsage();
        int corrected = 0;
        for (Map.Entry<VfsPath, long[]> e : fresh.entrySet()) {
            long[] old = usage.get(e.getKey());
            if (old == null || old[0] != e.getValue()[0] || old[1] != e.getValue()[1]) {
                corrected++;
            }
        }
        corrected += (int) usage.keySet().stream().filter(k -> !fresh.containsKey(k)).count();
        usage.clear();
        usage.putAll(fresh);
        return corrected;
    }

    private static boolean hasQuota(Map<String, String> m) {
        return m.containsKey(NamespaceAdmin.QUOTA_BYTES) || m.containsKey(NamespaceAdmin.QUOTA_FILES);
    }

    private void storeAttr(VfsPath dir, String key, String value) {
        if (value.isEmpty()) {
            attrs.computeIfPresent(dir, (k, m) -> {
                m.remove(key);
                return m.isEmpty() ? null : m;
            });
        } else {
            attrs.computeIfAbsent(dir, k -> new ConcurrentHashMap<>()).put(key, value);
        }
    }

    private static String normalizeAttr(String key, String value) {
        if (value.isEmpty()) {
            return value;
        }
        switch (key) {
            case NamespaceAdmin.QUOTA_BYTES, NamespaceAdmin.QUOTA_FILES, NamespaceAdmin.MAX_FILE_BYTES -> {
                try {
                    long n = Long.parseLong(value.trim());
                    if (n < 0) {
                        throw new NumberFormatException();
                    }
                    return Long.toString(n);
                } catch (NumberFormatException e) {
                    throw new FsException(FsErrorCode.INVALID_PATH, key + " must be a non-negative integer: " + value);
                }
            }
            default -> {
                String v = value.trim().toLowerCase(Locale.ROOT);
                if (!v.equals("true") && !v.equals("false")) {
                    throw new FsException(FsErrorCode.INVALID_PATH, key + " must be true or false: " + value);
                }
                return v;
            }
        }
    }

    @Override
    public synchronized void setAttr(VfsPath dir, String key, String value) throws FsException {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        checkAllowedNamespace(dir);
        checkVolumeAvailable(dir);
        if (!NamespaceAdmin.KEYS.contains(key)) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Unknown attribute " + key + " (known: "
                    + new TreeSet<>(NamespaceAdmin.KEYS) + ")");
        }
        if (isMountPoint(dir)) {
            getOrCreateMountPointInode(dir);
        } else {
            Inode inode = inodes.get(dir);
            if (inode == null) {
                throw new FsException(FsErrorCode.NOT_FOUND, "Directory not found: " + dir);
            }
            if (inode.type() != NodeType.DIRECTORY) {
                throw new FsException(FsErrorCode.NOT_A_DIRECTORY, "Not a directory: " + dir);
            }
        }
        String normalized = normalizeAttr(key, value);
        log(dir, new NamespaceRecord.SetAttr(dir.toString(), key, normalized));
        storeAttr(dir, key, normalized);
        Map<String, String> now = attrs.get(dir);
        if (now != null && hasQuota(now)) {
            usage.put(dir, scanUsage(dir));
        } else {
            usage.remove(dir);
        }
    }

    @Override
    public Map<String, String> attrsOf(VfsPath dir) throws FsException {
        Objects.requireNonNull(dir, "dir");
        Map<String, String> m = attrs.get(dir);
        return m == null ? Map.of() : new TreeMap<>(m);
    }

    @Override
    public synchronized long storedBytesOf(VfsPath dir) throws FsException {
        Objects.requireNonNull(dir, "dir");
        long total = 0;
        for (Inode inode : inodes.values()) {
            if (inode.type() != NodeType.FILE || !isUnder(inode.path(), dir)) {
                continue;
            }
            long size = Math.max(inode.size(), 0);
            StoragePolicy policy = inode.ref() != null && backend != null && inode.storageClass() != null
                    ? backend.catalog().policy(inode.storageClass()).orElse(null) : null;
            total += policy == null ? size : policy.storedBytesEstimate(size);
        }
        return total;
    }

    @Override
    public synchronized Usage usageOf(VfsPath dir) throws FsException {
        Objects.requireNonNull(dir, "dir");
        long[] u = scanUsage(dir);
        return new Usage(u[0], u[1]);
    }
}
