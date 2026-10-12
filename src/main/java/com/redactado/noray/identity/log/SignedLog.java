package com.redactado.noray.identity.log;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.identity.SigningKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An append-only, hash-chained, signed log with one owner and a set of writers the owner admits. This is the shape
 * of a namespace's version history: whoever stores or relays the log can withhold entries but cannot forge, edit,
 * reorder or drop one in the middle without the chain and the signatures failing. What a log holder can still do
 * is show an older prefix (rollback) or different histories to different readers (fork); both are detected by
 * comparing against a {@link HeadPin} or against another copy ({@link #findFork}).
 */
public final class SignedLog {

    private static final int MAGIC = 0x4E524C47; // "NRLG"

    private final List<LogEntry> entries = new ArrayList<>();
    private final Set<String> writers = new LinkedHashSet<>();
    private byte[] owner;
    private String name;

    private SignedLog() {
    }

    // ── Creation and appending ─────────────────────────────────────────────────

    public static SignedLog create(SigningKey owner, String name, Clock clock) {
        SignedLog log = new SignedLog();
        log.accept(LogEntry.create(owner, 0, LogEntry.ZERO_HASH, clock.millis(), LogEntry.Type.GENESIS,
                name.getBytes(StandardCharsets.UTF_8)));
        return log;
    }

    public synchronized LogEntry append(SigningKey signer, byte[] payload, Clock clock) throws FsException {
        return add(signer, LogEntry.Type.APPEND, payload, clock);
    }

    public synchronized LogEntry grant(SigningKey ownerKey, byte[] writerKey, Clock clock) throws FsException {
        return add(ownerKey, LogEntry.Type.GRANT, writerKey, clock);
    }

    public synchronized LogEntry revokeWriter(SigningKey ownerKey, byte[] writerKey, Clock clock) throws FsException {
        return add(ownerKey, LogEntry.Type.REVOKE, writerKey, clock);
    }

    private LogEntry add(SigningKey signer, LogEntry.Type type, byte[] payload, Clock clock) {
        LogEntry last = entries.get(entries.size() - 1);
        LogEntry e = LogEntry.create(signer, last.seq() + 1, last.hash(), clock.millis(), type, payload);
        accept(e);
        return e;
    }

    // ── Verification ───────────────────────────────────────────────────────────

    /** Validates one entry against the current head and state and appends it; throws and changes nothing if invalid. */
    private void accept(LogEntry e) {
        long expected = entries.size();
        if (e.seq() != expected) {
            throw denied("entry " + e.seq() + " where " + expected + " was expected");
        }
        byte[] expectedPrev = expected == 0 ? LogEntry.ZERO_HASH : entries.get(entries.size() - 1).hash();
        if (!Arrays.equals(e.prev(), expectedPrev)) {
            throw denied("entry " + e.seq() + " does not follow the previous one");
        }
        if (!e.signatureValid()) {
            throw denied("entry " + e.seq() + " has an invalid signature");
        }
        String signer = HexFormat.of().formatHex(e.signer());
        switch (e.type()) {
            case GENESIS -> {
                if (expected != 0) {
                    throw denied("a second genesis entry");
                }
                owner = e.signer();
                name = new String(e.payload(), StandardCharsets.UTF_8);
                writers.add(signer);
            }
            case APPEND -> {
                if (expected == 0 || !writers.contains(signer)) {
                    throw denied("entry " + e.seq() + " was signed by a key that is not a writer");
                }
            }
            case GRANT, REVOKE -> {
                if (expected == 0 || !Arrays.equals(e.signer(), owner)) {
                    throw denied("entry " + e.seq() + ": only the owner changes writers");
                }
                byte[] key = e.payload();
                if (key.length != 32) {
                    throw denied("entry " + e.seq() + " names a malformed key");
                }
                String k = HexFormat.of().formatHex(key);
                if (e.type() == LogEntry.Type.GRANT) {
                    writers.add(k);
                } else if (Arrays.equals(key, owner)) {
                    throw denied("the owner cannot be removed");
                } else {
                    writers.remove(k);
                }
            }
        }
        if (expected == 0 && e.type() != LogEntry.Type.GENESIS) {
            throw denied("a log starts with a genesis entry");
        }
        entries.add(e);
    }

    // ── Reading ────────────────────────────────────────────────────────────────

    public synchronized int size() { return entries.size(); }
    public synchronized byte[] owner() { return owner.clone(); }
    public synchronized String name() { return name; }
    public synchronized LogEntry head() { return entries.get(entries.size() - 1); }
    public synchronized LogEntry entry(int seq) { return entries.get(seq); }
    public synchronized List<LogEntry> entries() { return List.copyOf(entries); }
    public synchronized boolean isWriter(byte[] key) { return writers.contains(HexFormat.of().formatHex(key)); }
    public synchronized HeadPin pin() { return new HeadPin(head().seq(), head().hash()); }

    // ── Comparing copies ───────────────────────────────────────────────────────

    /** Evidence that two validly signed histories diverge: the first sequence number where they differ. */
    public record Fork(long seq, LogEntry ours, LogEntry theirs) {
    }

    public static Optional<Fork> findFork(SignedLog a, SignedLog b) {
        return findFork(a.entries(), b.entries());
    }

    private static Optional<Fork> findFork(List<LogEntry> ea, List<LogEntry> eb) {
        for (int i = 0; i < Math.min(ea.size(), eb.size()); i++) {
            if (!Arrays.equals(ea.get(i).hash(), eb.get(i).hash())) {
                return Optional.of(new Fork(i, ea.get(i), eb.get(i)));
            }
        }
        return Optional.empty();
    }

    /**
     * Takes over a longer copy that extends this one. Returns {@code false} when {@code other} has nothing new.
     *
     * @throws FsException {@code DENIED} when the copies diverge (a fork)
     */
    public boolean adopt(SignedLog other) throws FsException {
        List<LogEntry> theirs = other.entries(); // snapshot first: never hold two logs' locks at once
        synchronized (this) {
            Optional<Fork> fork = findFork(entries, theirs);
            if (fork.isPresent()) {
                throw denied("the logs fork at entry " + fork.get().seq());
            }
            if (theirs.size() <= entries.size()) {
                return false;
            }
            for (int i = entries.size(); i < theirs.size(); i++) {
                accept(theirs.get(i)); // re-validated against our own state
            }
            return true;
        }
    }

    // ── Partial copies (what is sent to witnesses) ─────────────────────────────

    /** Entries from {@code fromSeq} on, at most {@code max}. */
    public synchronized List<LogEntry> entriesFrom(int fromSeq, int max) {
        int from = Math.max(0, fromSeq);
        if (from >= entries.size()) {
            return List.of();
        }
        return List.copyOf(entries.subList(from, Math.min(entries.size(), from + max)));
    }

    /**
     * Adds entries that continue this log. Entries this log already has are skipped when identical.
     *
     * @return how many entries were new
     * @throws FsException {@code DENIED} on a fork (a different entry at a known position), a gap, or any entry that
     *                     fails verification; entries before the offending one stay accepted (each is valid)
     */
    public synchronized int extend(List<LogEntry> more) throws FsException {
        int added = 0;
        for (LogEntry e : more) {
            if (e.seq() < entries.size()) {
                if (!Arrays.equals(entries.get((int) e.seq()).hash(), e.hash())) {
                    throw denied("the log forks at entry " + e.seq());
                }
                continue;
            }
            accept(e);
            added++;
        }
        return added;
    }

    /** A log from its first entries (must start with the genesis entry). */
    public static SignedLog fromEntries(List<LogEntry> list) throws FsException {
        SignedLog log = new SignedLog();
        for (LogEntry e : list) {
            log.accept(e);
        }
        if (log.entries.isEmpty()) {
            throw denied("empty log");
        }
        return log;
    }

    public static byte[] encodeEntries(List<LogEntry> list) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            out.writeInt(list.size());
            for (LogEntry e : list) {
                byte[] body = e.body();
                out.writeInt(body.length);
                out.write(body);
                out.write(e.signatureBytes());
            }
            return buf.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses entries without verifying them (that happens when they are accepted by a log). */
    public static List<LogEntry> decodeEntries(byte[] bytes) throws FsException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int n = in.readInt();
            if (n < 0 || n > 4096) {
                throw denied("bad entry count");
            }
            List<LogEntry> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int len = in.readInt();
                if (len < 8 + 32 + 8 + 1 + 4 + 32 || len > LogEntry.MAX_PAYLOAD + 128) {
                    throw denied("bad entry length");
                }
                byte[] body = in.readNBytes(len);
                byte[] sig = in.readNBytes(64);
                if (body.length != len || sig.length != 64) {
                    throw denied("truncated entries");
                }
                out.add(parseEntry(body, sig));
            }
            if (in.available() != 0) {
                throw denied("trailing bytes");
            }
            return out;
        } catch (IOException e) {
            throw denied("truncated entries");
        } catch (IllegalArgumentException e) {
            throw denied("malformed entries: " + e.getMessage());
        }
    }

    // ── Serialisation ──────────────────────────────────────────────────────────

    public synchronized byte[] encode() {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            out.writeInt(MAGIC);
            out.writeInt(entries.size());
            for (LogEntry e : entries) {
                byte[] body = e.body();
                out.writeInt(body.length);
                out.write(body);
                out.write(e.signatureBytes());
            }
            return buf.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses and fully verifies a log: chain, signatures and who was allowed to write what. */
    public static SignedLog decode(byte[] bytes) throws FsException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) {
                throw denied("not a signed log");
            }
            int n = in.readInt();
            if (n < 1) {
                throw denied("empty log");
            }
            SignedLog log = new SignedLog();
            for (int i = 0; i < n; i++) {
                int len = in.readInt();
                if (len < 8 + 32 + 8 + 1 + 4 + 32 || len > LogEntry.MAX_PAYLOAD + 128) {
                    throw denied("bad entry length");
                }
                byte[] body = in.readNBytes(len);
                byte[] sig = in.readNBytes(64);
                if (body.length != len || sig.length != 64) {
                    throw denied("truncated log");
                }
                log.accept(parseEntry(body, sig));
            }
            if (in.available() != 0) {
                throw denied("trailing bytes after the last entry");
            }
            return log;
        } catch (IOException e) {
            throw denied("truncated log");
        } catch (IllegalArgumentException e) {
            throw denied("malformed log: " + e.getMessage());
        }
    }

    private static LogEntry parseEntry(byte[] body, byte[] sig) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        long seq = in.readLong();
        byte[] prev = in.readNBytes(32);
        long time = in.readLong();
        int t = in.readUnsignedByte();
        if (t >= LogEntry.Type.values().length) {
            throw new IllegalArgumentException("unknown entry type " + t);
        }
        int plen = in.readInt();
        if (plen < 0 || plen > LogEntry.MAX_PAYLOAD || plen != body.length - (8 + 32 + 8 + 1 + 4 + 32)) {
            throw new IllegalArgumentException("bad payload length");
        }
        byte[] payload = in.readNBytes(plen);
        byte[] signer = in.readNBytes(32);
        return new LogEntry(seq, prev, time, LogEntry.Type.values()[t], payload, signer, sig);
    }

    private static FsException denied(String why) {
        return new FsException(FsErrorCode.DENIED, why);
    }
}
