package com.redactado.noray.ops;

import com.redactado.noray.data.crypto.Keyring;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verified, encrypted backups of a node's data directory (everything needed to come back: keyring, node keys, namespace
 * journal, configuration, packages, web tokens; local blob stores only on request, since the mesh usually holds them).
 *
 * <pre>
 * "NRBAK1\n" | argon2 memoryKiB, iterations, parallelism (u32 each) | salt (16) | created millis (u64) | flags (u8)
 * then chunks: u32 length | ChaCha20-Poly1305(chunk)          (STREAM construction)
 * </pre>
 *
 * The key is Argon2id(passphrase, salt): a backup opens with the passphrase alone, without the keyring it contains. Each
 * chunk's nonce is its counter with a "last" bit, and the header is authenticated data of every chunk, so reordering,
 * truncation, a swapped header and any changed byte are all detected. Inside: for each file, its relative path, size,
 * bytes and SHA-256 (checked again after a restore writes it).
 */
public final class Backup {

    /** One file in a backup. */
    public record Entry(String path, long size, String sha256) {
    }

    private static final byte[] MAGIC = "NRBAK1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK = 1 << 20;
    private static final int FLAG_BLOBS = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Backup() {
    }

    // ── export ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Writes an encrypted backup of {@code dataDir} to {@code out} (atomically: a partial file never has the final name). */
    public static List<Entry> export(Path dataDir, Path out, char[] passphrase, boolean includeBlobs, Keyring.Params params)
            throws IOException {
        List<Path> files = select(dataDir, includeBlobs);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] header = header(params, salt, System.currentTimeMillis(), includeBlobs ? FLAG_BLOBS : 0);
        byte[] key = Keyring.stretch(passphrase, salt, params);
        List<Entry> entries = new ArrayList<>();
        Path tmp = out.resolveSibling(out.getFileName() + ".partial");
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(tmp))) {
            raw.write(header);
            try (SealingStream sealed = new SealingStream(raw, key, header); DataOutputStream d = new DataOutputStream(sealed)) {
                for (Path f : files) {
                    String rel = dataDir.relativize(f).toString().replace('\\', '/');
                    byte[] name = rel.getBytes(StandardCharsets.UTF_8);
                    long size = Files.size(f);
                    d.writeShort(name.length);
                    d.write(name);
                    d.writeLong(size);
                    MessageDigest md = sha256();
                    try (InputStream in = Files.newInputStream(f)) {
                        byte[] buf = new byte[64 * 1024];
                        long left = size;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException(rel + " shrank while it was being backed up");
                            d.write(buf, 0, n);
                            md.update(buf, 0, n);
                            left -= n;
                        }
                    }
                    byte[] digest = md.digest();
                    d.write(digest);
                    entries.add(new Entry(rel, size, hex(digest)));
                }
                d.writeShort(0); // end of entries
            }
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        restrict(tmp);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return entries;
    }

    /** Everything under the data directory except temporary files and, unless asked, local blob stores. */
    static List<Path> select(Path dataDir, boolean includeBlobs) throws IOException {
        try (Stream<Path> walk = Files.walk(dataDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp") && !p.getFileName().toString().endsWith(".partial"))
                    .filter(p -> includeBlobs || !dataDir.relativize(p).toString().replace('\\', '/').matches("(.*/)?blobs/.*"))
                    .sorted().toList();
        }
    }

    // ── verify and restore ────────────────────────────────────────────────────────────────────────────────────────────

    /** Decrypts and checks the whole backup without writing anything. Throws on a wrong passphrase or any damage. */
    public static List<Entry> verify(Path backup, char[] passphrase) throws IOException {
        return read(backup, passphrase, null, true);
    }

    /**
     * Restores into {@code target}, which must be empty unless {@code force}. With {@code dryRun} nothing is written and
     * the entries that would be are returned. Every file is checked against its recorded SHA-256 after writing.
     */
    public static List<Entry> restore(Path backup, char[] passphrase, Path target, boolean dryRun, boolean force)
            throws IOException {
        if (!dryRun && Files.isDirectory(target)) {
            try (Stream<Path> s = Files.list(target)) {
                if (s.findAny().isPresent() && !force) {
                    throw new IOException(target + " is not empty (a restore replaces a node's identity; use --force if you mean it)");
                }
            }
        }
        if (dryRun) return verify(backup, passphrase);
        verify(backup, passphrase); // never write anything from a backup that is not intact end to end
        return read(backup, passphrase, target, false);
    }

    private static List<Entry> read(Path backup, char[] passphrase, Path target, boolean verifyOnly) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(backup))) {
            byte[] header = raw.readNBytes(MAGIC.length + 12 + 16 + 8 + 1);
            if (header.length < MAGIC.length || !Arrays.equals(Arrays.copyOf(header, MAGIC.length), MAGIC)) {
                throw new IOException("not a Noray backup");
            }
            ByteBuffer h = ByteBuffer.wrap(header, MAGIC.length, header.length - MAGIC.length);
            int mem = h.getInt(), it = h.getInt(), par = h.getInt();
            if (mem > (1 << 20) || it > 10) {
                throw new IOException("backup asks for unreasonable Argon2 cost (" + mem + " KiB, " + it + " passes); refusing");
            }
            Keyring.Params params;
            try {
                params = new Keyring.Params(mem, it, par);
            } catch (IllegalArgumentException e) {
                throw new IOException("corrupt backup header: " + e.getMessage());
            }
            byte[] salt = new byte[16];
            h.get(salt);
            byte[] key = Keyring.stretch(passphrase, salt, params);
            List<Entry> entries = new ArrayList<>();
            try (DataInputStream d = new DataInputStream(new OpeningStream(raw, key, header))) {
                while (true) {
                    int nameLen = d.readUnsignedShort();
                    if (nameLen == 0) break;
                    String rel = new String(d.readNBytes(nameLen), StandardCharsets.UTF_8);
                    Path dest = target == null ? null : safeResolve(target, rel);
                    long size = d.readLong();
                    MessageDigest md = sha256();
                    OutputStream w = dest == null || verifyOnly ? OutputStream.nullOutputStream() : open(dest);
                    try (w) {
                        byte[] buf = new byte[64 * 1024];
                        long left = size;
                        while (left > 0) {
                            int n = d.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new EOFException("truncated entry " + rel);
                            md.update(buf, 0, n);
                            w.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    byte[] expected = d.readNBytes(32);
                    if (!MessageDigest.isEqual(expected, md.digest())) throw new IOException("checksum mismatch in " + rel);
                    if (dest != null && !verifyOnly && !MessageDigest.isEqual(expected, sha256().digest(Files.readAllBytes(dest)))) {
                        throw new IOException("restored " + rel + " does not match the backup");
                    }
                    entries.add(new Entry(rel, size, hex(expected)));
                }
                if (d.read() != -1) throw new IOException("trailing data after the last entry");
            } finally {
                Arrays.fill(key, (byte) 0);
            }
            return entries;
        }
    }

    /** Refuses absolute paths and any {@code ..}: a backup can only write below its target. */
    static Path safeResolve(Path target, String rel) throws IOException {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.contains("\0")) throw new IOException("unsafe path " + rel);
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) throw new IOException("unsafe path " + rel);
        }
        Path p = target.resolve(rel).normalize();
        if (!p.startsWith(target.normalize())) throw new IOException("unsafe path " + rel);
        return p;
    }

    private static OutputStream open(Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        OutputStream o = Files.newOutputStream(dest);
        restrict(dest);
        return o;
    }

    // ── chunked AEAD (STREAM) ─────────────────────────────────────────────────────────────────────────────────────────

    private static byte[] nonce(long counter, boolean last) {
        return ByteBuffer.allocate(12).putInt(last ? 0x80000000 : 0).putLong(counter).array();
    }

    private static Cipher cipher(int mode, byte[] key, long counter, boolean last, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce(counter, last)));
            c.updateAAD(aad);
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Buffers 1 MiB, seals it as one chunk; the chunk written by {@link #close()} is marked last. */
    private static final class SealingStream extends OutputStream {
        private final OutputStream out;
        private final byte[] key, aad;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(CHUNK);
        private long counter;
        private boolean closed;

        SealingStream(OutputStream out, byte[] key, byte[] aad) {
            this.out = out;
            this.key = key;
            this.aad = aad;
        }

        @Override public void write(int b) throws IOException {
            buf.write(b);
            if (buf.size() >= CHUNK) flushChunk(false);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, CHUNK - buf.size());
                buf.write(b, off, n);
                off += n;
                len -= n;
                if (buf.size() >= CHUNK) flushChunk(false);
            }
        }

        private void flushChunk(boolean last) throws IOException {
            try {
                byte[] sealed = cipher(Cipher.ENCRYPT_MODE, key, counter++, last, aad).doFinal(buf.toByteArray());
                new DataOutputStream(out).writeInt(sealed.length);
                out.write(sealed);
                buf.reset();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        /** Idempotent: wrapping streams close it too, and a second "last" chunk would make the backup unreadable. */
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            flushChunk(true);
            out.flush();
        }
    }

    /** Opens chunk by chunk; a stream that ends without its "last" chunk is truncated. */
    private static final class OpeningStream extends InputStream {
        private final DataInputStream in;
        private final byte[] key, aad;
        private byte[] chunk = new byte[0];
        private int pos;
        private long counter;
        private boolean ended;

        OpeningStream(InputStream in, byte[] key, byte[] aad) {
            this.in = new DataInputStream(in);
            this.key = key;
            this.aad = aad;
        }

        private boolean next() throws IOException {
            if (ended) return false;
            int len;
            try {
                len = in.readInt();
            } catch (EOFException e) {
                throw new IOException("backup is truncated (no final chunk)");
            }
            if (len < 16 || len > CHUNK + 16) throw new IOException("corrupt chunk length");
            byte[] sealed = in.readNBytes(len);
            if (sealed.length != len) throw new IOException("backup is truncated");
            for (boolean last : new boolean[]{false, true}) {
                try {
                    chunk = cipher(Cipher.DECRYPT_MODE, key, counter, last, aad).doFinal(sealed);
                    counter++;
                    pos = 0;
                    if (last) {
                        ended = true;
                        if (in.read() != -1) throw new IOException("data after the final chunk");
                    }
                    return true;
                } catch (AEADBadTagException e) {
                    // try the other flag; both failing means a wrong passphrase or damage
                } catch (GeneralSecurityException e) {
                    throw new IOException(e);
                }
            }
            throw new IOException("wrong passphrase, or the backup was modified (chunk " + counter + ")");
        }

        @Override public int read() throws IOException {
            while (pos >= chunk.length) if (!next()) return -1;
            return chunk[pos++] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            while (pos >= chunk.length) if (!next()) return -1;
            int n = Math.min(len, chunk.length - pos);
            System.arraycopy(chunk, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private static byte[] header(Keyring.Params p, byte[] salt, long created, int flags) {
        return ByteBuffer.allocate(MAGIC.length + 12 + 16 + 8 + 1).put(MAGIC).putInt(p.memoryKiB()).putInt(p.iterations())
                .putInt(p.parallelism()).put(salt).putLong(created).put((byte) flags).array();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    static void restrict(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not POSIX
        }
    }
}
