package com.redactado.noray.filesystem.store.remote;

import com.redactado.noray.data.crypto.EncryptedFile;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much of this node's disk each peer may hold. A blob is charged to the peer that stored it first (storing
 * something that is already here costs nothing); the charge ends when the blob is really removed. Unlike the
 * rate limiter, this bounds the total a compromised peer can ever occupy, not just how fast.
 *
 * <p>The ledger says which peer stored which blob, so on disk it is sealed with a keyring key when one is given.
 * It is written lazily ({@link #flush}); after a crash the last few charges may be missing, which only means a peer
 * is under-counted until its blobs are stored again. Blobs that were already here before the ledger existed are not
 * attributed until a peer stores them again (then that peer is charged); {@link #unattributed} shows what remains and
 * {@link #attributeUnclaimed} lets the operator assign it.
 */
public final class PeerQuota {

    private static final String LABEL = "noray/peer-quota/v1";

    private record Charge(NodeId peer, long size) {}

    private final long limitBytes;
    private final Map<NodeId, Long> overrides = new ConcurrentHashMap<>();
    private final Path file;      // null: not persisted
    private final byte[] sealKey; // null: stored in the clear (owner-only file)
    private final Map<ObjectId, Charge> charges = new ConcurrentHashMap<>();
    private final Map<NodeId, Long> used = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public PeerQuota(long limitBytes, Path file, byte[] sealKey) {
        if (limitBytes < 1) throw new IllegalArgumentException("limit must be positive");
        this.limitBytes = limitBytes;
        this.file = file;
        this.sealKey = sealKey == null ? null : sealKey.clone();
        load();
    }

    public long limitBytes() { return limitBytes; }

    /** A peer with its own limit (a bigger one for a trusted storage node, a smaller one for a reader that only caches). */
    public PeerQuota override(NodeId peer, long bytes) {
        if (bytes < 1) throw new IllegalArgumentException("limit must be positive");
        overrides.put(peer, bytes);
        return this;
    }

    public long limitFor(NodeId peer) { return overrides.getOrDefault(peer, limitBytes); }

    public long used(NodeId peer) { return used.getOrDefault(peer, 0L); }

    public Map<NodeId, Long> usage() { return new TreeMap<>(Comparator.comparing(NodeId::hex)) {{ putAll(used); }}; }

    /**
     * Charges {@code size} bytes of blob {@code id} to {@code peer} before it is stored.
     * No-op when the blob is already attributed to someone.
     *
     * @throws FsException DENIED when the peer would exceed its quota
     */
    public synchronized void reserve(NodeId peer, ObjectId id, long size) throws FsException {
        if (charges.containsKey(id)) return;
        long now = used.getOrDefault(peer, 0L);
        long limit = limitFor(peer);
        if (now + size > limit) {
            throw new FsException(FsErrorCode.DENIED,
                    "quota of " + limit + " bytes exceeded (" + now + " in use)");
        }
        charges.put(id, new Charge(peer, size));
        used.merge(peer, size, Long::sum);
        dirty = true;
    }

    /** Ends a charge (the blob is gone or was never stored). */
    public synchronized void release(ObjectId id) {
        Charge c = charges.remove(id);
        if (c != null) {
            used.compute(c.peer(), (p, v) -> v == null || v <= c.size() ? null : v - c.size());
            dirty = true;
        }
    }

    /** Drops charges for blobs no longer in the store (removed by garbage collection, sweeps or by hand). */
    public synchronized int reconcile(Set<ObjectId> present) {
        int n = 0;
        for (ObjectId id : new ArrayList<>(charges.keySet())) {
            if (!present.contains(id)) {
                release(id);
                n++;
            }
        }
        return n;
    }

    /** {count, bytes} of blobs in {@code store} that are charged to nobody. */
    public synchronized long[] unattributed(com.redactado.noray.filesystem.store.ObjectStore store) {
        long n = 0, bytes = 0;
        for (ObjectId id : store.listIds()) {
            if (charges.containsKey(id)) continue;
            n++;
            try {
                bytes += store.size(id);
            } catch (FsException ignored) {
                // removed meanwhile
            }
        }
        return new long[]{n, bytes};
    }

    /**
     * Charges every unattributed blob in {@code store} to {@code peer} (an operator decision: it may exceed the peer's
     * limit, which then only blocks its further writes). Returns how many blobs were charged.
     */
    public synchronized int attributeUnclaimed(NodeId peer, com.redactado.noray.filesystem.store.ObjectStore store) {
        int n = 0;
        for (ObjectId id : store.listIds()) {
            if (charges.containsKey(id)) continue;
            long size;
            try {
                size = store.size(id);
            } catch (FsException e) {
                continue;
            }
            charges.put(id, new Charge(peer, size));
            used.merge(peer, size, Long::sum);
            n++;
        }
        if (n > 0) {
            dirty = true;
            flush();
        }
        return n;
    }

    public synchronized void flush() {
        if (!dirty || file == null) return;
        StringBuilder sb = new StringBuilder();
        charges.forEach((id, c) -> sb.append(c.peer().hex()).append(' ').append(id.hex()).append(' ')
                .append(c.size()).append('\n'));
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (sealKey != null) {
                EncryptedFile.write(file, sealKey, LABEL, data);
            } else {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.deleteIfExists(tmp);
                SecureFiles.createOwnerOnly(tmp);
                Files.write(tmp, data);
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot write " + file, e);
        }
    }

    private void load() {
        if (file == null) return;
        try {
            byte[] data;
            if (sealKey != null) {
                data = EncryptedFile.read(file, sealKey, LABEL).orElse(null);
            } else {
                data = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            }
            if (data == null) return;
            for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                String[] p = line.split(" ");
                if (p.length != 3) throw new FsException(FsErrorCode.INTERNAL, "corrupt quota ledger " + file);
                ObjectId id = new ObjectId(p[1]);
                long size = Long.parseLong(p[2]);
                charges.put(id, new Charge(new NodeId(p[0]), size));
                used.merge(new NodeId(p[0]), size, Long::sum);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof FsException f) throw f;
            throw new FsException(FsErrorCode.INTERNAL, "cannot read quota ledger " + file, e);
        }
    }
}
