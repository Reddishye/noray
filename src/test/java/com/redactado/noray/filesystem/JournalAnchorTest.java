package com.redactado.noray.filesystem;

import com.redactado.noray.data.crypto.DataCrypto;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.vfs.journal.FileNamespaceJournal;
import com.redactado.noray.filesystem.vfs.journal.NamespaceRecord;
import com.redactado.noray.filesystem.vfs.journal.PayloadCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The sealed anchor next to an encrypted journal: truncation and replacement are detected, crashes are not mistaken for attacks. */
class JournalAnchorTest {

    @TempDir
    Path tmp;

    private final byte[] key = DataCrypto.randomKey();

    private FileNamespaceJournal open(Path file, boolean sync) {
        return new FileNamespaceJournal(file, sync, PayloadCipher.aead(key));
    }

    private static NamespaceRecord rec(String name, long inode) {
        return new NamespaceRecord.Create("/mnt/" + name, NodeType.FILE, inode, NamespaceRecord.NO_PARENT);
    }

    private void write(Path file, String prefix, int n, boolean sync) {
        try (FileNamespaceJournal j = open(file, sync)) {
            j.load();
            for (int i = 0; i < n; i++) {
                j.append(rec(prefix + i, i + 1));
            }
        }
    }

    private static Path anchor(Path journal) {
        return journal.resolveSibling(journal.getFileName() + ".anchor");
    }

    /** Offset just after the first {@code count} frames. */
    private static int endOfFrames(byte[] bytes, int count) {
        int pos = 5;
        for (int i = 0; i < count; i++) {
            pos += 8 + ByteBuffer.wrap(bytes).getInt(pos);
        }
        return pos;
    }

    private static void assertDenied(org.junit.jupiter.api.function.Executable e) {
        FsException ex = assertThrows(FsException.class, e);
        assertEquals(FsErrorCode.DENIED, ex.code(), ex.getMessage());
    }

    @Test
    void cleanRestartsKeepWorking() {
        Path file = tmp.resolve("j");
        write(file, "a", 5, false);
        assertTrue(Files.exists(anchor(file)));
        write(file, "b", 3, false);
        try (FileNamespaceJournal j = open(file, false)) {
            assertEquals(8, j.load().size());
        }
    }

    @Test
    void droppingTheEndOfTheJournalIsDetected() throws IOException {
        Path file = tmp.resolve("j");
        write(file, "a", 6, false);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, endOfFrames(bytes, 4))); // a clean cut after record 4
        assertDenied(() -> open(file, false).load());
    }

    @Test
    void swappingInAnotherHistoryOfTheSameLengthIsDetected() throws IOException {
        Path real = tmp.resolve("real");
        Path other = tmp.resolve("other");
        write(real, "a", 5, false);
        write(other, "z", 5, false);
        Files.copy(other, real, java.nio.file.StandardCopyOption.REPLACE_EXISTING); // anchor of "real" stays
        assertDenied(() -> open(real, false).load());
    }

    @Test
    void aTamperedAnchorIsRefusedAndAMissingOneIsRebuilt() throws IOException {
        Path file = tmp.resolve("j");
        write(file, "a", 4, false);
        byte[] a = Files.readAllBytes(anchor(file));
        a[a.length - 1] ^= 1;
        Files.write(anchor(file), a);
        assertDenied(() -> open(file, false).load());

        Files.delete(anchor(file));
        try (FileNamespaceJournal j = open(file, false)) {
            assertEquals(4, j.load().size());
        }
        assertTrue(Files.exists(anchor(file)), "anchored again after the next open");
    }

    @Test
    void aTornFinalWriteIsACrashNotAnAttack() throws IOException {
        Path file = tmp.resolve("j");
        write(file, "a", 3, true); // sync: anchor is exactly current
        // a crash while appending the 4th record leaves half a frame at the end
        byte[] bytes = Files.readAllBytes(file);
        try (var ch = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(new byte[]{0, 0, 0, 40, 1, 2, 3}));
        }
        try (FileNamespaceJournal j = open(file, true)) {
            assertEquals(3, j.load().size());
            j.append(rec("after-crash", 99));
        }
        try (FileNamespaceJournal j = open(file, true)) {
            assertEquals(4, j.load().size());
        }
        assertTrue(Files.size(file) > bytes.length);
    }

    @Test
    void compactionMovesTheAnchorWithIt() {
        Path file = tmp.resolve("j");
        try (FileNamespaceJournal j = open(file, false)) {
            j.load();
            for (int i = 0; i < 10; i++) j.append(rec("a" + i, i + 1));
            List<NamespaceRecord> snapshot = new ArrayList<>();
            snapshot.add(rec("kept", 1));
            j.rewrite(snapshot);
            j.append(rec("later", 2));
        }
        try (FileNamespaceJournal j = open(file, false)) {
            assertEquals(2, j.load().size());
        }
    }

    @Test
    void withoutAKeyThereIsNoAnchor() {
        Path file = tmp.resolve("plain");
        try (FileNamespaceJournal j = new FileNamespaceJournal(file, false)) {
            j.load();
            j.append(rec("a", 1));
        }
        assertFalse(Files.exists(anchor(file)));
    }
}
