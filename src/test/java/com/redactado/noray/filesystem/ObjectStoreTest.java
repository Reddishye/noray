package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ObjectStoreTest {

    @Test
    void objectIdValidation() {
        assertThrows(NullPointerException.class, () -> new ObjectId(null));
        assertThrows(IllegalArgumentException.class, () -> new ObjectId(""));
        assertThrows(IllegalArgumentException.class, () -> new ObjectId("ABCD1234"));
        assertThrows(IllegalArgumentException.class, () -> new ObjectId("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"));
        assertDoesNotThrow(() -> new ObjectId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    }

    @Test
    void putEmptyArrayAndDuplicateInsert() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        byte[] empty = new byte[0];
        PutResult result1 = store.put(empty);

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", result1.id().hex());
        assertEquals(0, result1.size());
        assertTrue(result1.inserted());

        PutResult result2 = store.put(empty);
        assertEquals(result1.id(), result2.id());
        assertFalse(result2.inserted());

        assertTrue(store.has(result1.id()));
        assertArrayEquals(empty, store.get(result1.id()));
        assertEquals(0, store.size(result1.id()));
    }

    @Test
    void getMissingThrowsNotFound() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        ObjectId missing = new ObjectId("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        FsException ex = assertThrows(FsException.class, () -> store.get(missing));
        assertEquals(FsErrorCode.NOT_FOUND, ex.code());
    }

    @Test
    void localDirObjectStoreRoundtripAndTamperDetection(@TempDir Path tempDir) throws IOException {
        LocalDirObjectStore store = new LocalDirObjectStore(tempDir);
        byte[] data = "Hello, Noray CAS!".getBytes(StandardCharsets.UTF_8);

        PutResult putResult = store.put(data);
        assertTrue(putResult.inserted());
        assertEquals(data.length, putResult.size());
        ObjectId id = putResult.id();

        assertTrue(store.has(id));
        assertEquals(data.length, store.size(id));
        assertArrayEquals(data, store.get(id));

        // Tamper with underlying file
        String hex = id.hex();
        Path filePath = tempDir.resolve("objects").resolve(hex.substring(0, 2)).resolve(hex.substring(2));
        assertTrue(Files.exists(filePath));
        Files.write(filePath, "Tampered content".getBytes(StandardCharsets.UTF_8));

        FsException ex = assertThrows(FsException.class, () -> store.get(id));
        assertEquals(FsErrorCode.DIGEST_MISMATCH, ex.code());
        assertFalse(Files.exists(filePath), "Corrupt file should be deleted on mismatch");
        assertFalse(store.has(id));
    }

    @Test
    void deleteRemovesObject() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        byte[] data = "some data".getBytes(StandardCharsets.UTF_8);
        PutResult res = store.put(data);

        assertTrue(store.has(res.id()));
        store.delete(res.id());
        assertFalse(store.has(res.id()));

        FsException ex = assertThrows(FsException.class, () -> store.delete(res.id()));
        assertEquals(FsErrorCode.NOT_FOUND, ex.code());
    }
}
