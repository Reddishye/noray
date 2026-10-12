package com.redactado.noray.ops;

import com.redactado.noray.data.crypto.Keyring;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The restore drill as a test, and the panic wipe. */
class BackupAndPanicTest {

    @TempDir
    Path tmp;

    private static final Keyring.Params FAST = new Keyring.Params(64, 1, 1);
    private static final char[] PASS = "correct horse battery staple".toCharArray();

    private Path node() throws IOException {
        Path d = tmp.resolve("data");
        byte[] big = new byte[3 * 1024 * 1024 + 17]; // spans several 1 MiB chunks
        new Random(1).nextBytes(big);
        write(d.resolve("keyring"), "key material".getBytes());
        write(d.resolve("net/local.keys.sealed"), "sealed keys".getBytes());
        write(d.resolve("fs/namespace.journal"), big);
        write(d.resolve("fs/blobs/objects/ab/cdef"), "a blob".getBytes());
        write(d.resolve("fs/volumes.properties.tmp"), "half-written".getBytes());
        write(d.resolve("identity/node.key"), "node key".getBytes());
        return d;
    }

    private static void write(Path p, byte[] b) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, b);
    }

    @Test
    void exportVerifyAndRestoreRoundTripByteForByte() throws IOException {
        Path data = node();
        Path bak = tmp.resolve("node.nrbak");
        var entries = Backup.export(data, bak, PASS, false, FAST);
        assertEquals(4, entries.size(), "blobs and temporary files are left out: " + entries);
        assertEquals(entries, Backup.verify(bak, PASS));

        Path target = tmp.resolve("restored");
        assertEquals(4, Backup.restore(bak, PASS, target, true, false).size());
        assertFalse(Files.exists(target), "a dry run writes nothing");
        Backup.restore(bak, PASS, target, false, false);
        for (var e : entries) {
            assertArrayEquals(Files.readAllBytes(data.resolve(e.path())), Files.readAllBytes(target.resolve(e.path())), e.path());
        }
        assertThrows(IOException.class, () -> Backup.restore(bak, PASS, target, false, false), "target not empty");

        assertEquals(5, Backup.export(data, tmp.resolve("all.nrbak"), PASS, true, FAST).size(), "--include-blobs");
    }

    @Test
    void wrongPassphraseTamperingAndTruncationAreDetected() throws IOException {
        Path bak = tmp.resolve("node.nrbak");
        Backup.export(node(), bak, PASS, false, FAST);
        assertTrue(assertThrows(IOException.class, () -> Backup.verify(bak, "wrong".toCharArray())).getMessage().contains("wrong passphrase"));

        byte[] good = Files.readAllBytes(bak);
        byte[] flipped = good.clone();
        flipped[good.length / 2] ^= 1;
        Files.write(tmp.resolve("flipped"), flipped);
        assertThrows(IOException.class, () -> Backup.verify(tmp.resolve("flipped"), PASS));

        byte[] header = good.clone();
        header[10] ^= 1; // Argon2 cost in the header: authenticated, so the key or the tags no longer match
        Files.write(tmp.resolve("header"), header);
        assertThrows(IOException.class, () -> Backup.verify(tmp.resolve("header"), PASS));

        // cut at a chunk boundary: every remaining chunk is valid, but the final one is missing
        int firstChunk = 7 + 12 + 16 + 8 + 1;
        int len = ByteBuffer.wrap(good, firstChunk, 4).getInt();
        Files.write(tmp.resolve("cut"), Arrays.copyOf(good, firstChunk + 4 + len));
        assertTrue(assertThrows(IOException.class, () -> Backup.verify(tmp.resolve("cut"), PASS)).getMessage().contains("truncated"));
        Path target = tmp.resolve("never");
        assertThrows(IOException.class, () -> Backup.restore(tmp.resolve("cut"), PASS, target, false, false));
        assertFalse(Files.exists(target), "nothing is written from a damaged backup");
    }

    @Test
    void restoredPathsStayInsideTheTarget() {
        assertThrows(IOException.class, () -> Backup.safeResolve(tmp, "../escape"));
        assertThrows(IOException.class, () -> Backup.safeResolve(tmp, "/etc/passwd"));
        assertThrows(IOException.class, () -> Backup.safeResolve(tmp, "a/../../b"));
    }

    @Test
    void panicWipeShredsKeysFirstAndOptionallyEverything() throws IOException {
        Path data = node();
        PanicWipe.Report r = PanicWipe.wipe(data, false);
        assertTrue(r.failures().isEmpty(), r.failures().toString());
        assertEquals(data.resolve("keyring"), r.shredded().getFirst(), "the keyring goes first");
        assertFalse(Files.exists(data.resolve("keyring")));
        assertFalse(Files.exists(data.resolve("identity/node.key")));
        assertFalse(Files.exists(data.resolve("fs/namespace.journal")));
        assertTrue(Files.exists(data.resolve("fs/blobs/objects/ab/cdef")), "blobs only with everything");
        PanicWipe.wipe(data, true);
        assertFalse(Files.exists(data.resolve("fs/blobs/objects/ab/cdef")));
    }
}
