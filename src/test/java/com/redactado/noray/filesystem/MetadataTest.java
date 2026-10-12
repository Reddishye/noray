package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.meta.StringMetadataItem;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MetadataTest {

    @Test
    void emptyMetadataGetReturnsEmptyOptional() {
        Metadata metadata = Metadata.empty();
        assertTrue(metadata.get("nonexistent").isEmpty());
        assertTrue(metadata.asMap().isEmpty());
        assertEquals("defaultVal", metadata.getString("nonexistent", "defaultVal"));
    }

    @Test
    void builderPutAndGetString() {
        Metadata metadata = Metadata.builder()
                .put("kind", "memory")
                .put("writable", "true")
                .put(new StringMetadataItem("tags", "ssd,fast"))
                .build();

        Optional<MetadataItem> kind = metadata.get("kind");
        assertTrue(kind.isPresent());
        assertEquals("memory", kind.get().value());
        assertEquals(String.class, kind.get().type());

        assertEquals("memory", metadata.getString("kind", "fallback"));
        assertEquals("true", metadata.getString("writable", "fallback"));
        assertEquals("ssd,fast", metadata.getString("tags", "fallback"));
        assertEquals("fallback", metadata.getString("missing", "fallback"));
    }

    @Test
    void metadataIsImmutable() {
        Metadata.Builder builder = Metadata.builder();
        builder.put("key1", "val1");
        Metadata metadata = builder.build();

        builder.put("key2", "val2");

        assertTrue(metadata.get("key1").isPresent());
        assertTrue(metadata.get("key2").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> metadata.asMap().put("key3", new StringMetadataItem("key3", "val3")));
    }

    @Test
    void fsExceptionHoldsCode() {
        FsException ex1 = new FsException(FsErrorCode.NOT_FOUND, "Not found error");
        assertEquals(FsErrorCode.NOT_FOUND, ex1.code());
        assertEquals("Not found error", ex1.getMessage());

        RuntimeException cause = new RuntimeException("root");
        FsException ex2 = new FsException(FsErrorCode.INTERNAL, "Internal error", cause);
        assertEquals(FsErrorCode.INTERNAL, ex2.code());
        assertEquals("Internal error", ex2.getMessage());
        assertSame(cause, ex2.getCause());
    }
}
