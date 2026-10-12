package com.redactado.noray.identity.witness;

import com.redactado.noray.data.crypto.EncryptedFile;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.vfs.journal.JournalState;
import com.redactado.noray.filesystem.vfs.journal.NotifyingJournal;
import com.redactado.noray.identity.Certificate;
import com.redactado.noray.identity.Op;
import com.redactado.noray.identity.PeerDirectory;
import com.redactado.noray.identity.SigningKey;
import com.redactado.noray.identity.log.LogEntry;
import com.redactado.noray.identity.log.SignedLog;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Keeps other nodes honest about this node's history, and this node honest about theirs.
 *
 * <p><b>Owner.</b> The node keeps a small signed, hash-chained log of <i>checkpoints</i> (journal record count and
 * newest-record hash) and hands every new entry to a few peers, its <i>witnesses</i>. Each cycle a witness answers
 * with the newest entry it holds, signed by this node. Restoring an old backup of the data directory (journal, anchor
 * and checkpoint log together, which no local check can notice) leaves the witnesses with entries this node no longer
 * has: that is detected and raised as an {@link Alarm}.
 *
 * <p><b>Witness.</b> The node stores, for each certified peer that sends it, that peer's checkpoint log. It verifies
 * every entry (signature, chain, and that the log is owned by the key in the peer's certificate) and keeps the first
 * history it saw: a peer that later presents a different one is recorded as having forked.
 *
 * <p>A witness learns only counts and hashes of an encrypted journal, never names or content. Alarms are evidence,
 * not verdicts: an operator who restored a backup on purpose will see one, and should re-establish the log.
 */
public final class WitnessService implements NotifyingJournal.Listener, AutoCloseable {

    public enum AlarmKind { JOURNAL_ROLLED_BACK, JOURNAL_REWRITTEN, LOG_ROLLED_BACK, LOG_FORKED, PEER_FORKED }

    public record Alarm(long timeMillis, AlarmKind kind, NodeId peer, String detail) {
        @Override public String toString() {
            return kind + (peer == null ? "" : " [" + peer.hex().substring(0, 12) + "…]") + ": " + detail;
        }
    }

    private static final System.Logger LOG = System.getLogger(WitnessService.class.getName());
    private static final byte PUBLISH = 1;
    private static final byte ACK = 2;
    private static final int BATCH = 256;
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES_PER_OWNER = 100_000;
    private static final int MAX_OWNERS = 256;
    private static final int MAX_ALARMS = 200;
    private static final String LABEL = "noray/witness/v1";
    static final String LOG_NAME = "noray/namespace-checkpoints/v1";
    static final int DEFAULT_ROTATE_AT = 20_000;

    private final MeshTransport transport;
    private final PeerDirectory directory;
    private final SigningKey key;
    private final Path dir;
    private final byte[] sealKey; // null: stored in the clear (owner-only files)
    private final Clock clock;
    private final int maxWitnesses;
    private final int rotateAt;
    private byte[] previousHead; // hash of the last entry of the previous epoch (null for the first one)
    private SignedLog previous;  // that epoch, kept to catch up witnesses that were away during the rotation
    private final java.util.function.BiConsumer<NodeId, byte[]> handler = this::onFrame;

    private SignedLog own;
    private volatile Supplier<JournalState> journalState = () -> null;
    private Checkpoint lastRecorded;
    private final Map<NodeId, Integer> witnessSize = new HashMap<>(); // what each witness acknowledged
    private final Map<NodeId, SignedLog> held = new HashMap<>();
    private final Deque<Alarm> alarms = new ArrayDeque<>();
    private ScheduledExecutorService scheduler;
    private boolean started;

    public WitnessService(MeshTransport transport, PeerDirectory directory, SigningKey key, Path dir, byte[] sealKey,
                          Clock clock, int maxWitnesses) {
        this(transport, directory, key, dir, sealKey, clock, maxWitnesses, DEFAULT_ROTATE_AT);
    }

    /** @param rotateAt entries after which the checkpoint log continues in a new, chained log */
    public WitnessService(MeshTransport transport, PeerDirectory directory, SigningKey key, Path dir, byte[] sealKey,
                          Clock clock, int maxWitnesses, int rotateAt) {
        this.rotateAt = Math.max(4, rotateAt);
        this.transport = Objects.requireNonNull(transport);
        this.directory = Objects.requireNonNull(directory);
        this.key = Objects.requireNonNull(key);
        this.dir = Objects.requireNonNull(dir);
        this.sealKey = sealKey == null ? null : sealKey.clone();
        this.clock = Objects.requireNonNull(clock);
        this.maxWitnesses = Math.max(1, maxWitnesses);
        loadOwn();
        loadHeld();
    }

    /** Where the journal's state comes from (set once the journal exists). */
    public void bind(Supplier<JournalState> journalState) {
        this.journalState = Objects.requireNonNull(journalState);
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    /** @param interval how often to checkpoint and publish; zero or less means only when {@link #runOnce} is called */
    public synchronized void start(java.time.Duration interval) {
        if (started) {
            return;
        }
        started = true;
        transport.subscribe(MeshChannel.WITNESS, handler);
        verifyLocal();
        if (interval.toMillis() > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "noray-witness");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    runOnce();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "witness: " + e.getMessage());
                }
            }, 5_000, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        started = false;
        transport.unsubscribe(MeshChannel.WITNESS, handler);
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    // ── owner role ─────────────────────────────────────────────────────────────

    /** Records the journal's state if it changed, then sends new entries (or just asks) to each witness. */
    public synchronized void runOnce() {
        JournalState st = journalState.get();
        if (st != null && (lastRecorded == null || !lastRecorded.sameStateAs(st.records(), st.newestHash()))) {
            record(new Checkpoint(Checkpoint.Kind.STATE, st.records(), st.newestHash()));
        }
        for (NodeId w : chooseWitnesses()) {
            publishTo(w);
        }
    }

    @Override
    public synchronized void beforeRewrite() {
        record(new Checkpoint(Checkpoint.Kind.COMPACTING, 0, null));
    }

    @Override
    public synchronized void afterRewrite() {
        JournalState st = journalState.get();
        if (st != null) {
            record(new Checkpoint(Checkpoint.Kind.STATE, st.records(), st.newestHash()));
        }
    }

    private void record(Checkpoint cp) {
        if (own.size() >= rotateAt && witnessSize.values().stream().anyMatch(n -> n >= own.size())) {
            rotate(); // only once a witness holds all of it, or the chain could not be followed
        }
        own.append(key, cp.encode(), clock);
        if (cp.kind() == Checkpoint.Kind.STATE) {
            lastRecorded = cp;
        }
        persist(ownFile(), own);
    }

    /**
     * Continues in a new log whose genesis names the hash of the old head, so witnesses that hold the whole old log
     * can follow the chain (and anybody else sees a restart as a fork). The old log is kept next to it.
     */
    private void rotate() {
        byte[] oldHead = own.head().hash();
        SignedLog old = own;
        try {
            Files.deleteIfExists(ownFile().resolveSibling("own.log.prev"));
            if (Files.exists(ownFile())) {
                Files.move(ownFile(), ownFile().resolveSibling("own.log.prev"));
            }
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot archive the checkpoint log", e);
        }
        own = SignedLog.create(key, LOG_NAME + "|prev=" + HexFormat.of().formatHex(oldHead), clock);
        previousHead = oldHead;
        previous = old;
        witnessSize.clear();
        persist(ownFile(), own);
    }

    private static byte[] previousOf(SignedLog log) {
        String name = log.name();
        int i = name.indexOf("|prev=");
        if (i < 0) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(name.substring(i + 6));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Peers with a verified certificate that may store here, spread by a hash of both ids. */
    List<NodeId> chooseWitnesses() {
        List<NodeId> candidates = new ArrayList<>();
        Set<NodeId> reachable = transport.peers();
        for (Map.Entry<NodeId, Certificate> e : directory.all().entrySet()) {
            if (reachable.contains(e.getKey()) && e.getValue().allows(Op.PUT)) {
                candidates.add(e.getKey());
            }
        }
        String me = transport.localId().hex();
        candidates.sort(Comparator.comparing(p -> sha(me + p.hex())));
        return candidates.size() > maxWitnesses ? candidates.subList(0, maxWitnesses) : candidates;
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void publishTo(NodeId witness) {
        int from = witnessSize.getOrDefault(witness, 0);
        send(witness, PUBLISH, SignedLog.encodeEntries(own.entriesFrom(from, BATCH)));
    }

    private void onAck(NodeId from, byte[] body) {
        List<LogEntry> heads = SignedLog.decodeEntries(body);
        if (heads.isEmpty()) { // the witness holds nothing of ours yet
            witnessSize.put(from, 0);
            publishTo(from);
            return;
        }
        LogEntry head = heads.get(0);
        // Only an entry signed by our own key counts as evidence: a witness cannot invent one.
        if (!Arrays.equals(head.signer(), key.publicKey()) || !head.signatureValid()) {
            LOG.log(System.Logger.Level.WARNING, "witness: " + from + " sent an entry that we did not sign");
            return;
        }
        if (previousHead != null && Arrays.equals(head.hash(), previousHead)) {
            // it holds our previous epoch completely and has not seen the new log yet
            witnessSize.put(from, 0);
            publishTo(from);
            return;
        }
        if (previous != null && head.seq() < previous.size()
                && Arrays.equals(previous.entry((int) head.seq()).hash(), head.hash())) {
            // it was away during the rotation: finish the old epoch first
            send(from, PUBLISH, SignedLog.encodeEntries(previous.entriesFrom((int) head.seq() + 1, BATCH)));
            return;
        }
        if (head.seq() >= own.size()) {
            alarm(AlarmKind.LOG_ROLLED_BACK, from, "a witness holds our checkpoint " + head.seq() + " but our own log ends at "
                    + (own.size() - 1) + ": this node's data directory was restored from an older copy"
                    + describe(head));
        } else if (!Arrays.equals(own.entry((int) head.seq()).hash(), head.hash())) {
            alarm(AlarmKind.LOG_FORKED, from, "a witness holds a different checkpoint " + head.seq()
                    + " than our log: two histories signed by this node's key exist");
        } else {
            witnessSize.put(from, (int) head.seq() + 1);
            if (head.seq() + 1 < own.size()) {
                publishTo(from);
            }
        }
    }

    private static String describe(LogEntry e) {
        try {
            if (e.type() == LogEntry.Type.APPEND) {
                Checkpoint cp = Checkpoint.decode(e.payload());
                if (cp.kind() == Checkpoint.Kind.STATE) {
                    return " (it recorded a journal of " + cp.records() + " records)";
                }
            }
        } catch (IllegalArgumentException ignored) {
            // not a checkpoint
        }
        return "";
    }

    /** Compares the journal with the newest checkpoint recorded locally (catches a restored journal at boot). */
    synchronized void verifyLocal() {
        Checkpoint last = null;
        for (int i = own.size() - 1; i > 0 && last == null; i--) {
            LogEntry e = own.entry(i);
            if (e.type() == LogEntry.Type.APPEND) {
                last = Checkpoint.decode(e.payload());
                if (last.kind() == Checkpoint.Kind.COMPACTING) {
                    return; // a rewrite was under way: nothing to compare against
                }
            }
        }
        JournalState st = journalState.get();
        if (last == null || st == null) {
            return;
        }
        if (st.records() < last.records()) {
            alarm(AlarmKind.JOURNAL_ROLLED_BACK, null, "the namespace journal has " + st.records()
                    + " records but a checkpoint recorded " + last.records() + ": changes were lost or an old copy was restored");
        } else if (st.records() == last.records() && st.newestHash().length > 0 && last.hash().length > 0
                && !Arrays.equals(st.newestHash(), last.hash())) {
            alarm(AlarmKind.JOURNAL_REWRITTEN, null, "the journal has as many records as the last checkpoint ("
                    + last.records() + ") but its newest record is a different one");
        }
    }

    // ── witness role ───────────────────────────────────────────────────────────

    private void onPublish(NodeId from, byte[] body) {
        try {
            directory.check(from, Op.PUT); // certified, not revoked or expired, allowed to store here
        } catch (FsException e) {
            return;
        }
        Certificate cert = directory.certificateOf(from).orElse(null);
        if (cert == null) {
            return;
        }
        List<LogEntry> entries = SignedLog.decodeEntries(body);
        SignedLog log = held.get(from);
        boolean changed = false;
        try {
            if (log == null) {
                if (held.size() >= MAX_OWNERS || entries.isEmpty() || entries.get(0).seq() != 0) {
                    sendAck(from, null);
                    return;
                }
                log = SignedLog.fromEntries(entries);
                if (!Arrays.equals(log.owner(), cert.signKey())) {
                    return; // a log that is not this peer's
                }
                held.put(from, log);
                changed = true;
            } else if (!entries.isEmpty() && entries.get(0).seq() == 0
                    && !Arrays.equals(entries.get(0).hash(), log.entry(0).hash()) && continuesOld(entries, log, cert)) {
                try {
                    Files.deleteIfExists(heldFile(from).resolveSibling(heldFile(from).getFileName() + ".prev"));
                    Files.move(heldFile(from), heldFile(from).resolveSibling(heldFile(from).getFileName() + ".prev"));
                } catch (IOException e) {
                    // keeping the archive is best effort
                }
                log = SignedLog.fromEntries(entries);
                held.put(from, log);
                changed = true;
            } else if (!entries.isEmpty() && entries.get(0).seq() == 0
                    && !Arrays.equals(entries.get(0).hash(), log.entry(0).hash()) && aheadOfUs(entries.get(0), log)) {
                // the owner rotated while we were away: it will finish the old log with us first
            } else {
                for (LogEntry e : entries) {
                    if (e.seq() < log.size() && !Arrays.equals(log.entry((int) e.seq()).hash(), e.hash())
                            && e.signatureValid() && Arrays.equals(e.signer(), log.owner())) {
                        alarm(AlarmKind.PEER_FORKED, from, "presented a different entry " + e.seq()
                                + " than the one already witnessed; the first history is kept");
                        break;
                    }
                }
                final SignedLog current = log;
                List<LogEntry> next = entries.stream().filter(e -> e.seq() >= current.size()).toList();
                if (!next.isEmpty() && next.get(0).seq() == current.size() && current.size() < MAX_ENTRIES_PER_OWNER) {
                    changed = current.extend(next) > 0;
                }
            }
        } catch (FsException e) {
            // malformed or unverifiable: keep what we had, still answer
        }
        if (changed) {
            persist(heldFile(from), held.get(from));
        }
        sendAck(from, held.get(from));
    }

    /**
     * A genesis signed by the owner that declares itself the continuation of an earlier log. We cannot tell whether
     * it follows what we hold (we may have missed the end of the old log), so we do not call it a fork: we answer with
     * our head and the owner completes the old log with us first. It replaces ours only once it provably follows it.
     */
    private static boolean aheadOfUs(LogEntry genesis, SignedLog held) {
        String name = new String(genesis.payload(), StandardCharsets.UTF_8);
        int i = name.indexOf("|prev=");
        if (i < 0 || !genesis.signatureValid() || !Arrays.equals(genesis.signer(), held.owner())) {
            return false;
        }
        try {
            return HexFormat.of().parseHex(name.substring(i + 6)).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether {@code entries} start a new log that provably follows everything we hold of the old one. */
    private boolean continuesOld(List<LogEntry> entries, SignedLog old, Certificate cert) {
        try {
            SignedLog next = SignedLog.fromEntries(entries);
            byte[] prev = previousOf(next);
            return prev != null && Arrays.equals(next.owner(), cert.signKey())
                    && Arrays.equals(prev, old.head().hash());
        } catch (FsException e) {
            return false;
        }
    }

    private void sendAck(NodeId to, SignedLog log) {
        send(to, ACK, SignedLog.encodeEntries(log == null ? List.of() : List.of(log.head())));
    }

    // ── wire ───────────────────────────────────────────────────────────────────

    private void send(NodeId peer, byte kind, byte[] body) {
        byte[] frame = new byte[body.length + 1];
        frame[0] = kind;
        System.arraycopy(body, 0, frame, 1, body.length);
        try {
            transport.send(peer, MeshChannel.WITNESS, frame);
        } catch (FsException ignored) {
            // unreachable: the next cycle tries again
        }
    }

    private void onFrame(NodeId from, byte[] frame) {
        if (frame == null || frame.length < 2 || frame.length > MAX_FRAME) {
            return;
        }
        byte[] body = Arrays.copyOfRange(frame, 1, frame.length);
        try {
            synchronized (this) {
                switch (frame[0]) {
                    case PUBLISH -> onPublish(from, body);
                    case ACK -> onAck(from, body);
                    default -> { }
                }
            }
        } catch (RuntimeException e) {
            // a malformed frame is dropped
        }
    }

    // ── alarms, status ─────────────────────────────────────────────────────────

    private void alarm(AlarmKind kind, NodeId peer, String detail) {
        Alarm a = new Alarm(clock.millis(), kind, peer, detail);
        if (!alarms.isEmpty() && alarms.peekLast().kind() == kind && Objects.equals(alarms.peekLast().peer(), peer)
                && alarms.peekLast().detail().equals(detail)) {
            return; // the same finding again
        }
        if (alarms.size() == MAX_ALARMS) {
            alarms.removeFirst();
        }
        alarms.addLast(a);
        com.redactado.noray.kernel.metrics.Metrics.global().inc("witness.alarms");
        LOG.log(System.Logger.Level.ERROR, "WITNESS ALARM: " + a);
    }

    public synchronized List<Alarm> alarms() { return List.copyOf(alarms); }

    public synchronized void clearAlarms() { alarms.clear(); }

    /**
     * The alarm that says this node's own history was tampered with (rolled back, rewritten, forked), as text; null if
     * there is none. A fork reported <i>by a peer's</i> log says nothing about us and does not count.
     */
    public synchronized String integrityAlarm() {
        for (Alarm a : alarms) {
            if (a.kind() != AlarmKind.PEER_FORKED) {
                return a.kind() + ": " + a.detail() + " (inspect, then `witness clear`)";
            }
        }
        return null;
    }

    public synchronized int ownCheckpoints() { return own.size() - 1; }

    public synchronized Map<NodeId, Integer> witnessAcknowledged() { return new TreeMap<>(Comparator.comparing(NodeId::hex)) {{ putAll(witnessSize); }}; }

    /** A revoked peer's log is of no use to anyone and costs disk: forget it (and refuse to take it again). */
    public synchronized boolean forget(NodeId peer) {
        boolean had = held.remove(peer) != null;
        try {
            Files.deleteIfExists(heldFile(peer));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "witness: cannot delete the log of " + peer.hex() + ": " + e.getMessage());
        }
        witnessSize.remove(peer);
        if (had) {
            com.redactado.noray.kernel.metrics.Metrics.global().inc("witness.logs.dropped");
        }
        return had;
    }

    /** Peers whose logs this node keeps, with how many entries. */
    public synchronized Map<NodeId, Integer> held() {
        Map<NodeId, Integer> out = new TreeMap<>(Comparator.comparing(NodeId::hex));
        held.forEach((p, l) -> out.put(p, l.size()));
        return out;
    }

    // ── persistence ────────────────────────────────────────────────────────────

    private Path ownFile() { return dir.resolve("own.log"); }
    private Path heldFile(NodeId peer) { return dir.resolve("held-" + peer.hex() + ".log"); }

    private void persist(Path file, SignedLog log) {
        byte[] data = log.encode();
        try {
            if (sealKey != null) {
                EncryptedFile.write(file, sealKey, LABEL, data);
            } else {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.deleteIfExists(tmp);
                SecureFiles.createOwnerOnly(tmp);
                Files.write(tmp, data);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot write " + file, e);
        }
    }

    private Optional<SignedLog> read(Path file) {
        try {
            byte[] data = sealKey != null ? EncryptedFile.read(file, sealKey, LABEL).orElse(null)
                    : (Files.isRegularFile(file) ? Files.readAllBytes(file) : null);
            return data == null ? Optional.empty() : Optional.of(SignedLog.decode(data));
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot read " + file, e);
        }
    }

    private void loadOwn() {
        // A damaged or foreign file stops the node rather than silently starting a new history.
        Optional<SignedLog> found = read(ownFile());
        if (found.isPresent() && Arrays.equals(found.get().owner(), key.publicKey())) {
            own = found.get();
            previousHead = previousOf(own);
            if (previousHead != null) {
                try {
                    SignedLog prev = read(ownFile().resolveSibling("own.log.prev")).orElse(null);
                    previous = prev != null && Arrays.equals(prev.head().hash(), previousHead) ? prev : null;
                } catch (FsException e) {
                    previous = null; // the archive is only a convenience
                }
            }
            for (int i = own.size() - 1; i > 0; i--) {
                LogEntry e = own.entry(i);
                if (e.type() == LogEntry.Type.APPEND) {
                    Checkpoint cp = Checkpoint.decode(e.payload());
                    if (cp.kind() == Checkpoint.Kind.STATE) {
                        lastRecorded = cp;
                        break;
                    }
                }
            }
            return;
        }
        if (found.isPresent()) {
            throw new FsException(FsErrorCode.DENIED, "the checkpoint log in " + ownFile()
                    + " belongs to another key; move it away if this node's identity was replaced on purpose");
        }
        own = SignedLog.create(key, LOG_NAME, clock);
        persist(ownFile(), own);
    }

    private void loadHeld() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (n.startsWith("held-") && n.endsWith(".log")) {
                    String hex = n.substring(5, n.length() - 4);
                    read(f).ifPresent(l -> held.put(new NodeId(hex), l));
                }
            }
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot list " + dir, e);
        }
    }
}
