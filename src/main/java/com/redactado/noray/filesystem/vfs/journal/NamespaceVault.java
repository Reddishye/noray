package com.redactado.noray.filesystem.vfs.journal;

import com.redactado.noray.data.crypto.DataCrypto;
import com.redactado.noray.data.crypto.EncryptedFile;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.identity.Certificate;
import com.redactado.noray.identity.Op;
import com.redactado.noray.identity.PeerDirectory;
import com.redactado.noray.kernel.metrics.Metrics;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * The namespace (who has which file, which keys, which policy) is the one piece of state that otherwise lives on a
 * single node. The vault removes that single point of loss: the node seals a snapshot of it with a key only it holds
 * (the keyring's) and hands the sealed bytes to a few certified peers, which keep one copy per owner and give it back
 * on request. A peer learns nothing from what it keeps and can at worst withhold it or return an older copy; the
 * owner takes the newest copy that opens. After losing the disk the node recovers with its passphrase and the mesh.
 *
 * <p>Limits: a snapshot is shipped whole (it must fit {@link #MAX_SNAPSHOT_BYTES}), and a copy is as fresh as the last
 * push (default every minute, when something changed).
 */
public final class NamespaceVault implements AutoCloseable {

    static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private static final byte STORE = 1, STORED = 2, FETCH = 3, BLOB = 4;
    private static final byte[] AAD = "noray/namespace-vault/v1".getBytes(StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger("noray.vault");

    private final MeshTransport transport;
    private final PeerDirectory directory;
    private final byte[] sealKey;
    private final Path dir;
    private final Supplier<List<NamespaceRecord>> snapshot;
    private final Supplier<JournalState> state;
    private final int copies;
    private final java.util.function.BiConsumer<NodeId, byte[]> handler = this::onFrame;
    private volatile NamespaceJournal restoreTarget;

    private final Map<NodeId, Long> acknowledged = new HashMap<>(); // holder -> version it confirmed
    private final Map<NodeId, byte[]> fetched = new ConcurrentHashMap<>();
    private volatile CountDownLatch fetchWait;
    private JournalState lastPushedState;
    private long version;
    private ScheduledExecutorService scheduler;
    private boolean started;

    public NamespaceVault(MeshTransport transport, PeerDirectory directory, byte[] sealKey, Path dir,
                          Supplier<List<NamespaceRecord>> snapshot, Supplier<JournalState> state, int copies) {
        this.transport = Objects.requireNonNull(transport);
        this.directory = Objects.requireNonNull(directory);
        this.sealKey = Objects.requireNonNull(sealKey).clone();
        this.dir = Objects.requireNonNull(dir);
        this.snapshot = Objects.requireNonNull(snapshot);
        this.state = Objects.requireNonNull(state);
        this.copies = Math.max(1, copies);
        this.version = System.currentTimeMillis();
    }

    public synchronized void start(Duration interval) {
        if (started) return;
        started = true;
        transport.subscribe(MeshChannel.NAMESPACE, handler);
        if (interval.toMillis() > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "noray-vault");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    push(false);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "vault: " + e.getMessage());
                }
            }, 20_000, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void close() {
        if (!started) return;
        started = false;
        transport.unsubscribe(MeshChannel.NAMESPACE, handler);
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    // ── owner side ─────────────────────────────────────────────────────────────

    /** Ships a fresh sealed snapshot to the holders if the journal changed since the last push (or {@code force}). */
    public synchronized int push(boolean force) {
        JournalState now = state.get();
        if (!force && lastPushedState != null && now != null && lastPushedState.sameAs(now)) {
            return 0;
        }
        byte[] plain = NamespaceRecordCodec.encode(snapshot.get());
        if (plain.length > MAX_SNAPSHOT_BYTES) {
            LOG.log(System.Logger.Level.WARNING, "vault: the namespace (" + plain.length + " bytes) is too large to replicate");
            return 0;
        }
        version = Math.max(version + 1, System.currentTimeMillis());
        byte[] sealed = DataCrypto.sealRandomNonce(sealKey, AAD, plain);
        byte[] msg = ByteBuffer.allocate(1 + 8 + sealed.length).put(STORE).putLong(version).put(sealed).array();
        int sent = 0;
        for (NodeId holder : holders()) {
            try {
                transport.send(holder, MeshChannel.NAMESPACE, msg);
                sent++;
            } catch (FsException e) {
                LOG.log(System.Logger.Level.DEBUG, "vault: cannot reach " + holder.hex() + ": " + e.getMessage());
            }
        }
        if (sent > 0) {
            lastPushedState = now;
            Metrics.global().inc("vault.pushes");
        }
        return sent;
    }

    /** Holders that confirmed which version (the newest confirmed copy is what a recovery would find). */
    public synchronized Map<NodeId, Long> acknowledged() {
        return new TreeMap<>(Comparator.comparing(NodeId::hex)) {{ putAll(acknowledged); }};
    }

    /**
     * Asks every reachable peer for the copy it keeps of our namespace and returns the newest one that opens with our
     * key. Empty when nobody has one (or the key is not the one it was sealed with).
     */
    public Optional<List<NamespaceRecord>> recover(Duration wait) throws InterruptedException {
        fetched.clear();
        Set<NodeId> asked = new HashSet<>();
        for (NodeId p : transport.peers()) {
            try {
                transport.send(p, MeshChannel.NAMESPACE, new byte[]{FETCH});
                asked.add(p);
            } catch (FsException ignored) {
                // an unreachable peer simply does not answer
            }
        }
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end && fetched.size() < asked.size()) {
            Thread.sleep(50);
        }
        long bestVersion = -1;
        List<NamespaceRecord> best = null;
        for (byte[] blob : fetched.values()) {
            if (blob.length < 8) continue;
            long v = ByteBuffer.wrap(blob).getLong();
            if (v <= bestVersion) continue;
            try {
                byte[] plain = DataCrypto.openRandomNonce(sealKey, AAD, Arrays.copyOfRange(blob, 8, blob.length));
                best = NamespaceRecordCodec.decode(plain);
                bestVersion = v;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "vault: a copy does not open with our key (tampered or not ours)");
            }
        }
        return Optional.ofNullable(best);
    }

    /** The journal a recovery writes into (the one on disk, not a wrapper that guards or watches it). */
    public void bindRestore(NamespaceJournal journal) {
        this.restoreTarget = journal;
    }

    /**
     * Rebuilds the journal file from the newest copy the mesh holds. Refuses to overwrite a journal that has records
     * unless {@code force}. The running namespace does not change: restart the node afterwards.
     *
     * @return the number of records restored
     */
    public int restore(boolean force, Duration wait) throws InterruptedException {
        NamespaceJournal target = restoreTarget;
        if (target == null) {
            throw new IllegalStateException("no journal to restore into");
        }
        if (!force && target.recordCount() > 0) {
            throw new IllegalStateException("this node's journal is not empty (" + target.recordCount()
                    + " records); use --force to replace it");
        }
        List<NamespaceRecord> records = recover(wait).orElseThrow(
                () -> new IllegalStateException("no peer returned a copy that opens with this node's key"));
        target.rewrite(records);
        return records.size();
    }

    private List<NodeId> holders() {
        List<NodeId> candidates = new ArrayList<>();
        Set<NodeId> reachable = transport.peers();
        for (Map.Entry<NodeId, Certificate> e : directory.all().entrySet()) {
            if (reachable.contains(e.getKey()) && e.getValue().allows(Op.PUT)) {
                candidates.add(e.getKey());
            }
        }
        candidates.sort(Comparator.comparing(NodeId::hex));
        return candidates.size() > copies ? candidates.subList(0, copies) : candidates;
    }

    // ── holder side and replies ────────────────────────────────────────────────

    private void onFrame(NodeId from, byte[] body) {
        if (body.length < 1) return;
        try {
            switch (body[0]) {
                case STORE -> onStore(from, body);
                case STORED -> {
                    if (body.length == 9) {
                        synchronized (this) {
                            acknowledged.put(from, ByteBuffer.wrap(body, 1, 8).getLong());
                        }
                    }
                }
                case FETCH -> onFetch(from);
                case BLOB -> {
                    if (body.length > 9 && body.length <= MAX_SNAPSHOT_BYTES + 256) {
                        fetched.put(from, Arrays.copyOfRange(body, 1, body.length));
                    }
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "vault: dropped a frame from " + from.hex() + ": " + e.getMessage());
        }
    }

    private Path file(NodeId owner) {
        return dir.resolve("vault-" + owner.hex() + ".blob");
    }

    private synchronized void onStore(NodeId from, byte[] body) {
        try {
            directory.check(from, Op.PUT);
        } catch (FsException e) {
            return;
        }
        if (body.length < 10 || body.length > MAX_SNAPSHOT_BYTES + 256) return;
        long v = ByteBuffer.wrap(body, 1, 8).getLong();
        try {
            Path f = file(from);
            if (Files.isRegularFile(f)) {
                byte[] have = Files.readAllBytes(f);
                if (have.length >= 8 && ByteBuffer.wrap(have).getLong() >= v) {
                    reply(from, STORED, ByteBuffer.wrap(have).getLong()); // already holds a newer one
                    return;
                }
            }
            Files.createDirectories(dir);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            SecureFiles.createOwnerOnly(tmp);
            Files.write(tmp, Arrays.copyOfRange(body, 1, body.length));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            Metrics.global().inc("vault.stored");
            reply(from, STORED, v);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "vault: cannot keep the copy of " + from.hex() + ": " + e.getMessage());
        }
    }

    private synchronized void onFetch(NodeId from) throws RuntimeException {
        try {
            directory.check(from, Op.GET);
            Path f = file(from);
            if (!Files.isRegularFile(f)) return;
            byte[] blob = Files.readAllBytes(f);
            transport.send(from, MeshChannel.NAMESPACE, ByteBuffer.allocate(1 + blob.length).put(BLOB).put(blob).array());
        } catch (IOException | FsException e) {
            LOG.log(System.Logger.Level.DEBUG, "vault: fetch from " + from.hex() + ": " + e.getMessage());
        }
    }

    private void reply(NodeId to, byte type, long v) {
        try {
            transport.send(to, MeshChannel.NAMESPACE, ByteBuffer.allocate(9).put(type).putLong(v).array());
        } catch (FsException ignored) {
            // the owner will push again
        }
    }

    /** Owners whose copies this node keeps. */
    public synchronized List<NodeId> keptFor() {
        List<NodeId> out = new ArrayList<>();
        try (var s = Files.isDirectory(dir) ? Files.list(dir) : java.util.stream.Stream.<Path>empty()) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.startsWith("vault-") && n.endsWith(".blob")) {
                    out.add(new NodeId(n.substring(6, n.length() - 5)));
                }
            });
        } catch (IOException ignored) {
            // nothing kept
        }
        out.sort(Comparator.comparing(NodeId::hex));
        return out;
    }

    /** A revoked owner's copy is of no use to anybody. */
    public synchronized void forget(NodeId owner) {
        try {
            Files.deleteIfExists(file(owner));
        } catch (IOException ignored) {
            // best effort
        }
    }
}
