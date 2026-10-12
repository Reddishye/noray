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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class EncryptedJournalTest {

    @TempDir
    Path tmp;

    private final byte[] key = DataCrypto.randomKey();

    private static List<NamespaceRecord> sample() {
        return List.of(
                new NamespaceRecord.Create("/mnt/secret-project", NodeType.DIRECTORY, 1, NamespaceRecord.NO_PARENT),
                new NamespaceRecord.Create("/mnt/secret-project/plan.txt", NodeType.FILE, 2, 1),
                new NamespaceRecord.SetFile("/mnt/secret-project/plan.txt", "noray-ref:2:abc", "tiered"),
                new NamespaceRecord.SetPolicy("/mnt/secret-project", "archive"));
    }

    private void write(Path file, byte[] k, List<NamespaceRecord> records) {
        try (FileNamespaceJournal j = new FileNamespaceJournal(file, false, k == null ? null : PayloadCipher.aead(k))) {
            j.load();
            records.forEach(j::append);
        }
    }

    private List<NamespaceRecord> read(Path file, byte[] k) {
        try (FileNamespaceJournal j = new FileNamespaceJournal(file, false, k == null ? null : PayloadCipher.aead(k))) {
            return j.load();
        }
    }

    @Test
    void encryptedJournalRoundTripsAndHidesEverything() throws IOException {
        Path file = tmp.resolve("ns.journal");
        write(file, key, sample());
        assertEquals(sample(), read(file, key));
        String raw = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("secret-project") || raw.contains("plan.txt") || raw.contains("noray-ref"));
    }

    @Test
    void wrongKeyOrMissingKeyIsRefused() {
        Path file = tmp.resolve("ns.journal");
        write(file, key, sample());
        assertEquals(FsErrorCode.DENIED,
                assertThrows(FsException.class, () -> read(file, DataCrypto.randomKey())).code());
        assertEquals(FsErrorCode.DENIED, assertThrows(FsException.class, () -> read(file, null)).code());
    }

    @Test
    void plaintextJournalIsMigratedWhenAKeyAppears() throws IOException {
        Path file = tmp.resolve("ns.journal");
        write(file, null, sample().subList(0, 2));
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("plan.txt"));

        assertEquals(sample().subList(0, 2), read(file, key)); // opening with a key migrates in place
        assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("plan.txt"));
        assertEquals(sample().subList(0, 2), read(file, key));
        assertThrows(FsException.class, () -> read(file, null));
    }

    @Test
    void appendAfterReopenKeepsSequenceAndCompactionResetsIt() {
        Path file = tmp.resolve("ns.journal");
        write(file, key, sample().subList(0, 2));
        write(file, key, sample().subList(2, 4)); // reopen: sequence continues at 2
        assertEquals(sample(), read(file, key));

        try (FileNamespaceJournal j = new FileNamespaceJournal(file, false, PayloadCipher.aead(key))) {
            j.load();
            j.rewrite(sample().subList(1, 3));
            j.append(sample().get(3));
        }
        assertEquals(List.of(sample().get(1), sample().get(2), sample().get(3)), read(file, key));
    }

    /** Frame offsets of a journal file: {start, length-with-header}. */
    private static List<int[]> frames(byte[] bytes) {
        List<int[]> out = new ArrayList<>();
        int pos = 5;
        while (pos < bytes.length) {
            int len = ByteBuffer.wrap(bytes).getInt(pos);
            out.add(new int[]{pos, 8 + len});
            pos += 8 + len;
        }
        return out;
    }

    @Test
    void tamperingWithAValidlyChecksummedRecordIsDetected() throws IOException {
        Path file = tmp.resolve("ns.journal");
        write(file, key, sample());
        byte[] bytes = Files.readAllBytes(file);
        int[] f = frames(bytes).get(1);
        bytes[f[0] + 8 + 20] ^= 1; // flip a ciphertext bit...
        CRC32 crc = new CRC32(); // ...and fix the CRC so only the AEAD tag can notice
        crc.update(bytes, f[0] + 8, f[1] - 8);
        ByteBuffer.wrap(bytes).putInt(f[0] + 4, (int) crc.getValue());
        Files.write(file, bytes);
        assertEquals(FsErrorCode.DENIED, assertThrows(FsException.class, () -> read(file, key)).code());
    }

    @Test
    void reorderingRecordsIsDetected() throws IOException {
        Path file = tmp.resolve("ns.journal");
        write(file, key, sample());
        byte[] bytes = Files.readAllBytes(file);
        List<int[]> fr = frames(bytes);
        byte[] a = Arrays.copyOfRange(bytes, fr.get(0)[0], fr.get(0)[0] + fr.get(0)[1]);
        byte[] b = Arrays.copyOfRange(bytes, fr.get(1)[0], fr.get(1)[0] + fr.get(1)[1]);
        byte[] swapped = bytes.clone();
        System.arraycopy(b, 0, swapped, fr.get(0)[0], b.length);
        System.arraycopy(a, 0, swapped, fr.get(0)[0] + b.length, a.length);
        Files.write(file, swapped);
        assertEquals(FsErrorCode.DENIED, assertThrows(FsException.class, () -> read(file, key)).code());
    }

    @Test
    void protectsSecretsReflectsEncryption() {
        assertFalse(new FileNamespaceJournal(tmp.resolve("a"), false).protectsSecrets());
        assertTrue(new FileNamespaceJournal(tmp.resolve("b"), false, PayloadCipher.aead(key)).protectsSecrets());
        assertTrue(com.redactado.noray.filesystem.vfs.journal.NamespaceJournal.none().protectsSecrets());
    }
}
