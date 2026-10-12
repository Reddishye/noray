package com.redactado.noray.data;

import com.redactado.noray.data.crypto.DataCrypto;
import com.redactado.noray.data.crypto.EncryptedFile;
import com.redactado.noray.data.crypto.Keyring;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KeyringTest {

    private static final Keyring.Params FAST = new Keyring.Params(64, 1, 1);

    @TempDir
    Path tmp;

    @Test
    void createThenUnlockGivesTheSameKeys() throws Exception {
        Path file = tmp.resolve("keyring");
        byte[] first;
        try (Keyring k = Keyring.openOrCreate(file, "correct horse".toCharArray(), FAST)) {
            first = k.subKey("journal");
            assertEquals(32, first.length);
            assertFalse(Arrays.equals(first, k.subKey("heat")), "purposes must get different keys");
        }
        try (Keyring k = Keyring.openOrCreate(file, "correct horse".toCharArray(), FAST)) {
            assertArrayEquals(first, k.subKey("journal"));
        }
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
    }

    @Test
    void wrongPassphraseIsRejectedAndDoesNotOverwriteTheKeyring() throws Exception {
        Path file = tmp.resolve("keyring");
        byte[] key;
        try (Keyring k = Keyring.openOrCreate(file, "right".toCharArray(), FAST)) {
            key = k.subKey("x");
        }
        byte[] before = Files.readAllBytes(file);
        FsException e = assertThrows(FsException.class, () -> Keyring.openOrCreate(file, "wrong".toCharArray(), FAST));
        assertEquals(FsErrorCode.DENIED, e.code());
        assertArrayEquals(before, Files.readAllBytes(file));
        try (Keyring k = Keyring.openOrCreate(file, "right".toCharArray(), FAST)) {
            assertArrayEquals(key, k.subKey("x"));
        }
    }

    @Test
    void emptyPassphraseAndGarbageFilesAreRefused() throws Exception {
        assertThrows(FsException.class, () -> Keyring.openOrCreate(tmp.resolve("k"), new char[0], FAST));
        Path junk = tmp.resolve("junk");
        Files.write(junk, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(RuntimeException.class, () -> Keyring.openOrCreate(junk, "x".toCharArray(), FAST));
    }

    @Test
    void closedKeyringHandsOutNoKeys() {
        Keyring k = Keyring.openOrCreate(tmp.resolve("keyring"), "pw".toCharArray(), FAST);
        k.close();
        assertFalse(k.isOpen());
        assertThrows(IllegalStateException.class, () -> k.subKey("x"));
    }

    @Test
    void randomNonceSealingRoundTripsAndDetectsTampering() {
        byte[] key = DataCrypto.randomKey();
        byte[] aad = {1};
        byte[] a = DataCrypto.sealRandomNonce(key, aad, "hello".getBytes());
        byte[] b = DataCrypto.sealRandomNonce(key, aad, "hello".getBytes());
        assertFalse(Arrays.equals(a, b), "fresh nonce every time");
        assertArrayEquals("hello".getBytes(), DataCrypto.openRandomNonce(key, aad, a));
        a[a.length - 1] ^= 1;
        assertThrows(FsException.class, () -> DataCrypto.openRandomNonce(key, aad, a));
        assertThrows(FsException.class, () -> DataCrypto.openRandomNonce(key, aad, new byte[5]));
    }

    @Test
    void encryptedFileRoundTrip() throws Exception {
        Path f = tmp.resolve("heat.bin");
        byte[] key = DataCrypto.randomKey();
        assertTrue(EncryptedFile.read(f, key, "l").isEmpty());
        EncryptedFile.write(f, key, "l", "secret stats".getBytes());
        assertFalse(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1).contains("secret"));
        assertArrayEquals("secret stats".getBytes(), EncryptedFile.read(f, key, "l").orElseThrow());
        assertThrows(FsException.class, () -> EncryptedFile.read(f, key, "other-label"));
        assertThrows(FsException.class, () -> EncryptedFile.read(f, DataCrypto.randomKey(), "l"));
    }
}
