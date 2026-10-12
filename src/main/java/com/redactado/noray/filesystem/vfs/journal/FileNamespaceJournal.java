package com.redactado.noray.filesystem.vfs.journal;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.util.SecureFiles;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * File-backed {@link NamespaceJournal}.
 *
 * <p>Layout: {@code MAGIC(4) VERSION(1)} followed by frames {@code length(4) crc32(4) payload(length)}.
 * Version 1 stores records in clear; version 2 stores each record sealed by a {@link PayloadCipher}
 * (the CRC then covers the sealed bytes, so a torn write is still told apart from tampering, which fails
 * authentication and is reported as an error). Opening a version 1 journal with a cipher migrates it to
 * version 2 in place; opening a version 2 journal without a cipher fails.
 * A frame is only trusted if its CRC matches. A torn frame at the very end of the file (a crash while
 * appending) is discarded and truncated; corruption anywhere else is reported as an error instead of
 * being silently skipped. The file is created with owner-only permissions.
 *
 * <p><b>Anchor.</b> An encrypted journal keeps a small sealed side file ({@code <journal>.anchor}) holding the
 * record count and a hash of the newest frame. Opening refuses a journal that has fewer records than the anchor
 * (a truncated tail) or whose record at the anchored position is a different one (replaced by another history).
 * The anchor lags by at most {@value #ANCHOR_EVERY} appends unless {@code sync} is on, and it cannot stop an attacker
 * who restores an old journal <i>and</i> its old anchor together: that needs a copy kept elsewhere (a witness
 * node, a {@code HeadPin}).
 */
public final class FileNamespaceJournal implements NamespaceJournal {

    private static final byte[] MAGIC = {'N', 'R', 'Y', 'J'};
    private static final byte VERSION_PLAIN = 1;
    private static final byte VERSION_ENCRYPTED = 2;
    private static final int HEADER_SIZE = MAGIC.length + 1;
    private static final int MAX_PAYLOAD = 1 << 20;

    private static final byte T_CREATE = 1;
    private static final byte T_UNLINK = 2;
    private static final byte T_SET_DATA = 3;
    private static final byte T_SET_FILE = 4;
    private static final byte T_SET_POLICY = 5;
    private static final byte T_SET_ATTR = 6;

    private final Path file;
    private final boolean sync;
    private final PayloadCipher cipher;

    private static final long ANCHOR_SEQUENCE = -1L;
    private static final int ANCHOR_EVERY = 64;
    private static final System.Logger LOG = System.getLogger(FileNamespaceJournal.class.getName());

    private FileChannel channel;
    private long records;
    private boolean loaded;
    /** Hash of the newest frame (encrypted journals only): what the anchor commits to. */
    private byte[] lastFrameHash = new byte[0];
    private int sinceAnchor;
    // filled by parse() for the anchor check
    private long probeIndex = -1;
    private byte[] probeHash;
    private boolean anchorWarned;

    /**
     * @param file journal file
     * @param sync if true, every append is forced to stable storage before returning
     */
    public FileNamespaceJournal(Path file, boolean sync) {
        this(file, sync, null);
    }

    /** @param cipher encrypts records at rest; {@code null} stores them in clear */
    public FileNamespaceJournal(Path file, boolean sync, PayloadCipher cipher) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        this.sync = sync;
        this.cipher = cipher;
    }

    private byte version() {
        return cipher != null ? VERSION_ENCRYPTED : VERSION_PLAIN;
    }

    @Override
    public boolean protectsSecrets() {
        return cipher != null;
    }

    @Override
    public synchronized List<NamespaceRecord> load() throws FsException {
        List<NamespaceRecord> out = new ArrayList<>();
        replay(out::add);
        return out;
    }

    @Override
    public synchronized void replay(Consumer<NamespaceRecord> sink) throws FsException {
        if (loaded) {
            throw new IllegalStateException("journal already loaded");
        }
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                SecureFiles.createOwnerOnly(file);
            } else {
                SecureFiles.restrictToOwner(file);
            }
            long size = Files.size(file);
            long goodEnd;
            long[] anchored = readAnchor();
            probeIndex = anchored == null ? -1 : anchored[0] - 1;
            probeHash = null;
            lastFrameHash = new byte[0];
            boolean migrate = false;
            long count = 0;
            List<NamespaceRecord> toMigrate = null;
            if (size == 0) {
                goodEnd = 0;
            } else {
                try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ)) {
                    Reader reader = new Reader(in, size);
                    migrate = size >= HEADER_SIZE && cipher != null && reader.at(MAGIC.length, 1)[0] == VERSION_PLAIN;
                    if (migrate) {
                        toMigrate = new ArrayList<>();
                    }
                    List<NamespaceRecord> keep = toMigrate;
                    long[] counted = new long[1];
                    goodEnd = scan(reader, size, r -> {
                        if (keep != null) keep.add(r);
                        sink.accept(r);
                    }, counted);
                    count = counted[0];
                    migrate = migrate && goodEnd != 0;
                }
            }
            channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
            if (goodEnd == 0) {
                channel.truncate(0);
                writeFully(ByteBuffer.wrap(header()), 0);
                goodEnd = HEADER_SIZE;
            } else if (goodEnd < size) {
                channel.truncate(goodEnd); // drop a torn tail left by a crash
            }
            channel.position(goodEnd);
            if (sync) {
                channel.force(true);
            }
            records = count;
            verifyAnchor(anchored, (int) count);
            loaded = true;
            if (migrate) {
                rewrite(toMigrate); // plaintext journal found while a key is available: encrypt it now
            } else if (cipher != null && records > 0 && anchored == null) {
                writeAnchor(); // first time this journal is anchored
            }
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Failed to load namespace journal " + file, e);
        }
    }

    /** Positional reads through a 1 MiB window, so that a journal is never held in memory as a whole. */
    private static final class Reader {
        private static final int WINDOW = 1 << 20;
        private final FileChannel in;
        private final long size;
        private long start = -1;
        private byte[] buf = new byte[0];

        Reader(FileChannel in, long size) {
            this.in = in;
            this.size = size;
        }

        byte[] at(long pos, int n) throws IOException {
            if (pos < start || pos + n > start + buf.length) {
                int want = (int) Math.min(Math.max(n, WINDOW), size - pos);
                ByteBuffer b = ByteBuffer.allocate(want);
                long p = pos;
                while (b.hasRemaining()) {
                    int r = in.read(b, p);
                    if (r < 0) break;
                    p += r;
                }
                buf = Arrays.copyOf(b.array(), b.position());
                start = pos;
            }
            return Arrays.copyOfRange(buf, (int) (pos - start), (int) (pos - start) + n);
        }

        boolean restIsZero(long from) throws IOException {
            long pos = from;
            while (pos < size) {
                int n = (int) Math.min(WINDOW, size - pos);
                for (byte x : at(pos, n)) {
                    if (x != 0) return false;
                }
                pos += n;
            }
            return true;
        }
    }

    /** Parses all frames, handing each record to {@code sink}; returns the offset just after the last valid one. */
    private long scan(Reader in, long size, Consumer<NamespaceRecord> sink, long[] counted) throws IOException {
        if (size < HEADER_SIZE) {
            return 0; // header itself was torn; start over
        }
        byte[] header = in.at(0, HEADER_SIZE);
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) {
                throw new FsException(FsErrorCode.INTERNAL, "Not a namespace journal: " + file);
            }
        }
        byte fileVersion = header[MAGIC.length];
        if (fileVersion != VERSION_PLAIN && fileVersion != VERSION_ENCRYPTED) {
            throw new FsException(FsErrorCode.INTERNAL,
                    "Unsupported namespace journal version " + fileVersion + ": " + file);
        }
        if (fileVersion == VERSION_ENCRYPTED && cipher == null) {
            throw new FsException(FsErrorCode.DENIED,
                    "Namespace journal is encrypted but no key is available: " + file);
        }
        boolean encrypted = fileVersion == VERSION_ENCRYPTED;
        long pos = HEADER_SIZE;
        long n = 0;
        while (pos < size) {
            if (size - pos < 8) {
                counted[0] = n;
                return pos; // torn frame header
            }
            ByteBuffer fh = ByteBuffer.wrap(in.at(pos, 8));
            int len = fh.getInt();
            int crc = fh.getInt();
            if (len < 1 || len > MAX_PAYLOAD) {
                // zero-filled space left by a crash while the file was being extended is a torn tail
                counted[0] = n;
                return handleBadFrame(pos, "invalid frame length " + len, in.restIsZero(pos));
            }
            long frameEnd = pos + 8 + len;
            if (frameEnd > size) {
                counted[0] = n;
                return pos; // torn payload
            }
            byte[] payload = in.at(pos + 8, len);
            CRC32 crc32 = new CRC32();
            crc32.update(payload, 0, len);
            if ((int) crc32.getValue() != crc) {
                counted[0] = n;
                return handleBadFrame(pos, "checksum mismatch", frameEnd == size);
            }
            try {
                if (encrypted) {
                    lastFrameHash = frameHash(payload);
                    if (n == probeIndex) {
                        probeHash = lastFrameHash;
                    }
                    try {
                        payload = cipher.open(payload, n);
                    } catch (FsException e) {
                        throw new FsException(FsErrorCode.DENIED, "Namespace journal record " + n
                                + " failed authentication (wrong key or tampered file): " + file, e);
                    }
                }
                sink.accept(decode(ByteBuffer.wrap(payload)));
            } catch (FsException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new FsException(FsErrorCode.INTERNAL,
                        "Corrupt record at offset " + pos + " in namespace journal " + file, e);
            }
            n++;
            pos = frameEnd;
        }
        counted[0] = n;
        return pos;
    }

    private long handleBadFrame(long pos, String why, boolean isTornTail) {
        if (isTornTail) {
            return pos; // a partially flushed final frame: safe to drop
        }
        throw new FsException(FsErrorCode.INTERNAL,
                "Namespace journal corrupted at offset " + pos + " (" + why + "): " + file);
    }

    @Override
    public synchronized void append(NamespaceRecord record) throws FsException {
        Objects.requireNonNull(record, "record");
        requireOpen();
        try {
            ByteBuffer frame = ByteBuffer.wrap(frame(record, records));
            long position = channel.size();
            byte[] frameBytes = frame.array();
            writeFully(frame, position);
            if (sync) {
                channel.force(false);
            }
            records++;
            if (cipher != null) {
                lastFrameHash = frameHash(Arrays.copyOfRange(frameBytes, 8, frameBytes.length));
                if (sync || ++sinceAnchor >= ANCHOR_EVERY) {
                    writeAnchor();
                }
            }
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Failed to append to namespace journal", e);
        }
    }

    @Override
    public synchronized JournalState state() {
        return new JournalState(records, cipher != null && records > 0 ? lastFrameHash : null);
    }

    @Override
    public synchronized long recordCount() {
        return records;
    }

    @Override
    public synchronized void rewrite(List<NamespaceRecord> snapshot) throws FsException {
        requireOpen();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.deleteIfExists(tmp);
            SecureFiles.createOwnerOnly(tmp);
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                baos.write(header());
                long seq = 0;
                byte[] newLast = new byte[0];
                for (NamespaceRecord r : snapshot) {
                    byte[] f = frame(r, seq++);
                    baos.write(f);
                    newLast = cipher != null ? frameHash(Arrays.copyOfRange(f, 8, f.length)) : newLast;
                }
                lastFrameHash = newLast;
                ByteBuffer all = ByteBuffer.wrap(baos.toByteArray());
                while (all.hasRemaining()) {
                    out.write(all);
                }
                out.force(true);
            }
            channel.close();
            if (cipher != null) {
                // compaction shrinks the record count: a crash between the move and the new anchor must not leave
                // an anchor that vouches for the old, longer journal (it would refuse a perfectly good one)
                Files.deleteIfExists(anchorFile());
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
            channel.position(channel.size());
            records = snapshot.size();
            if (cipher != null) {
                if (records > 0) {
                    writeAnchor();
                } else {
                    Files.deleteIfExists(anchorFile());
                }
            }
        } catch (IOException e) {
            // Try to keep the journal usable even if compaction failed.
            try {
                if (!channel.isOpen()) {
                    channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                }
            } catch (IOException ignored) {
                // surfaced below
            }
            throw new FsException(FsErrorCode.INTERNAL, "Failed to compact namespace journal", e);
        }
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            try {
                if (sync || cipher != null) {
                    channel.force(true);
                }
                if (cipher != null && records > 0 && sinceAnchor > 0) {
                    writeAnchor();
                }
                channel.close();
            } catch (IOException ignored) {
                // nothing sensible to do on close
            }
            channel = null;
        }
    }

    // ── anchor ─────────────────────────────────────────────────────────────────

    private Path anchorFile() {
        return file.resolveSibling(file.getFileName() + ".anchor");
    }

    private static byte[] frameHash(byte[] sealedPayload) {
        try {
            return Arrays.copyOf(java.security.MessageDigest.getInstance("SHA-256").digest(sealedPayload), 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {count} of the anchored state, or null when there is none (or no cipher). The hash is checked later. */
    private long[] readAnchor() throws IOException {
        anchorHash = null;
        if (cipher == null || !Files.isRegularFile(anchorFile())) {
            return null;
        }
        byte[] plain;
        try {
            plain = cipher.open(Files.readAllBytes(anchorFile()), ANCHOR_SEQUENCE);
        } catch (FsException e) {
            throw new FsException(FsErrorCode.DENIED,
                    "Namespace journal anchor failed authentication (wrong key or tampered): " + anchorFile(), e);
        }
        if (plain.length != 8 + 16) {
            throw new FsException(FsErrorCode.DENIED, "Namespace journal anchor is malformed: " + anchorFile());
        }
        ByteBuffer b = ByteBuffer.wrap(plain);
        long count = b.getLong();
        anchorHash = new byte[16];
        b.get(anchorHash);
        return new long[]{count};
    }

    private byte[] anchorHash;

    private void verifyAnchor(long[] anchored, int found) {
        if (anchored == null) {
            return;
        }
        if (found < anchored[0]) {
            throw new FsException(FsErrorCode.DENIED, "Namespace journal was truncated: " + anchored[0]
                    + " records were committed but only " + found + " are present: " + file);
        }
        if (anchored[0] > 0 && !Arrays.equals(probeHash, anchorHash)) {
            throw new FsException(FsErrorCode.DENIED,
                    "Namespace journal does not match its anchor (replaced or rewritten): " + file);
        }
    }

    /** Best effort: the record is already durable, so a failure here must not fail the operation. */
    private void writeAnchor() {
        try {
            if (!sync) {
                channel.force(false); // the anchor must never get ahead of the data it vouches for
            }
            ByteBuffer plain = ByteBuffer.allocate(8 + 16).putLong(records).put(lastFrameHash);
            byte[] sealed = cipher.seal(plain.array(), ANCHOR_SEQUENCE);
            Path tmp = anchorFile().resolveSibling(anchorFile().getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            SecureFiles.createOwnerOnly(tmp);
            Files.write(tmp, sealed);
            try {
                Files.move(tmp, anchorFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, anchorFile(), StandardCopyOption.REPLACE_EXISTING);
            }
            sinceAnchor = 0;
        } catch (IOException | RuntimeException e) {
            if (!anchorWarned) {
                anchorWarned = true;
                LOG.log(System.Logger.Level.WARNING, "namespace journal anchor could not be written: " + e.getMessage());
            }
        }
    }

    // ── encoding ───────────────────────────────────────────────────────────────

    private void requireOpen() {
        if (!loaded || channel == null || !channel.isOpen()) {
            throw new IllegalStateException("journal is not open");
        }
    }

    private void writeFully(ByteBuffer buf, long position) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            p += channel.write(buf, p);
        }
    }

    private byte[] header() {
        byte[] h = new byte[HEADER_SIZE];
        System.arraycopy(MAGIC, 0, h, 0, MAGIC.length);
        h[MAGIC.length] = version();
        return h;
    }

    private byte[] frame(NamespaceRecord record, long sequence) throws IOException {
        byte[] payload = encode(record);
        if (cipher != null) {
            payload = cipher.seal(payload, sequence);
        }
        if (payload.length > MAX_PAYLOAD) {
            throw new FsException(FsErrorCode.INVALID_PATH, "Namespace record too large");
        }
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length);
        b.putInt(payload.length);
        b.putInt((int) crc.getValue());
        b.put(payload);
        return b.array();
    }

    static byte[] encode(NamespaceRecord record) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        switch (record) {
            case NamespaceRecord.Create c -> {
                out.writeByte(T_CREATE);
                writeString(out, c.path());
                out.writeByte(typeCode(c.type()));
                out.writeLong(c.inodeId());
                out.writeLong(c.parentId());
            }
            case NamespaceRecord.Unlink u -> {
                out.writeByte(T_UNLINK);
                writeString(out, u.path());
            }
            case NamespaceRecord.SetData s -> {
                out.writeByte(T_SET_DATA);
                writeString(out, s.path());
                writeString(out, s.objectIdHex());
                out.writeLong(s.size());
            }
            case NamespaceRecord.SetFile f -> {
                out.writeByte(T_SET_FILE);
                writeString(out, f.path());
                writeString(out, f.fileRef());
                writeString(out, f.policy());
                out.writeLong(f.size());
            }
            case NamespaceRecord.SetAttr a -> {
                out.writeByte(T_SET_ATTR);
                writeString(out, a.path());
                writeString(out, a.key());
                writeString(out, a.value());
            }
            case NamespaceRecord.SetPolicy p -> {
                out.writeByte(T_SET_POLICY);
                writeString(out, p.path());
                writeString(out, p.policy());
            }
        }
        out.flush();
        return baos.toByteArray();
    }

    /** Malformed input fails with {@link IllegalArgumentException}, never with a buffer exception (fuzz-tested). */
    static NamespaceRecord decode(ByteBuffer b) {
        try {
            return decodeRecord(b);
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated namespace record", e);
        }
    }

    private static NamespaceRecord decodeRecord(ByteBuffer b) {
        byte type = b.get();
        return switch (type) {
            case T_CREATE -> {
                String path = readString(b);
                NodeType nodeType = typeFromCode(b.get());
                long inode = b.getLong();
                long parent = b.getLong();
                yield new NamespaceRecord.Create(path, nodeType, inode, parent);
            }
            case T_UNLINK -> new NamespaceRecord.Unlink(readString(b));
            case T_SET_DATA -> {
                String path = readString(b);
                String id = readString(b);
                yield new NamespaceRecord.SetData(path, id, b.remaining() >= 8 ? b.getLong() : NamespaceRecord.UNKNOWN_SIZE);
            }
            case T_SET_FILE -> {
                String path = readString(b);
                String ref = readString(b);
                String policy = readString(b);
                yield new NamespaceRecord.SetFile(path, ref, policy,
                        b.remaining() >= 8 ? b.getLong() : NamespaceRecord.UNKNOWN_SIZE);
            }
            case T_SET_POLICY -> new NamespaceRecord.SetPolicy(readString(b), readString(b));
            case T_SET_ATTR -> new NamespaceRecord.SetAttr(readString(b), readString(b), readString(b));
            default -> throw new IllegalArgumentException("unknown record type " + type);
        };
    }

    // Explicit on-disk codes: never persist enum ordinals, reordering the enum must not corrupt data.
    private static byte typeCode(NodeType type) {
        return switch (type) {
            case DIRECTORY -> 1;
            case FILE -> 2;
            case SYMLINK -> 3;
        };
    }

    private static NodeType typeFromCode(byte code) {
        return switch (code) {
            case 1 -> NodeType.DIRECTORY;
            case 2 -> NodeType.FILE;
            case 3 -> NodeType.SYMLINK;
            default -> throw new IllegalArgumentException("unknown node type code " + code);
        };
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(ByteBuffer b) {
        int len = b.getInt();
        if (len < 0 || len > b.remaining()) {
            throw new IllegalArgumentException("invalid string length " + len);
        }
        byte[] bytes = new byte[len];
        b.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
