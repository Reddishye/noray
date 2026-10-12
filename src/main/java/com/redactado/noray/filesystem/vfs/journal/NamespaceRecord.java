package com.redactado.noray.filesystem.vfs.journal;

import com.redactado.noray.filesystem.api.NodeType;

import java.util.Objects;

/** A single durable change to the local namespace. */
public sealed interface NamespaceRecord {

    /** Marker for "no parent inode" (e.g. the implicit inode of a mount point). */
    long NO_PARENT = -1L;

    /** An inode (file or directory) was created at {@code path}. */
    record Create(String path, NodeType type, long inodeId, long parentId) implements NamespaceRecord {
        public Create {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(type, "type");
        }
    }

    /** The inode at {@code path} was removed. */
    record Unlink(String path) implements NamespaceRecord {
        public Unlink {
            Objects.requireNonNull(path, "path");
        }
    }

    /** Size marker of records written before sizes were journaled. */
    long UNKNOWN_SIZE = -1L;

    /** The file at {@code path} now points to the blob {@code objectIdHex} ({@code size} bytes of content). */
    record SetData(String path, String objectIdHex, long size) implements NamespaceRecord {
        public SetData {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(objectIdHex, "objectIdHex");
        }

        public SetData(String path, String objectIdHex) {
            this(path, objectIdHex, UNKNOWN_SIZE);
        }
    }

    /**
     * The file at {@code path} is stored by the data engine. {@code fileRef} is the encoded
     * {@link com.redactado.noray.data.manifest.FileRef} (<b>contains the file key</b>, so the journal must be
     * encrypted) and {@code policy} the name of the storage policy it was written with.
     */
    record SetFile(String path, String fileRef, String policy, long size) implements NamespaceRecord {
        public SetFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(fileRef, "fileRef");
            Objects.requireNonNull(policy, "policy");
        }

        public SetFile(String path, String fileRef, String policy) {
            this(path, fileRef, policy, UNKNOWN_SIZE);
        }
    }

    /** The directory at {@code path} now has the storage policy {@code policy} (empty: inherit). */
    record SetPolicy(String path, String policy) implements NamespaceRecord {
        public SetPolicy {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(policy, "policy");
        }
    }

    /**
     * A directory attribute: quotas and permissions ({@code quota.bytes}, {@code quota.files}, {@code readonly},
     * {@code deny.delete}...). An empty {@code value} removes the attribute.
     */
    record SetAttr(String path, String key, String value) implements NamespaceRecord {
        public SetAttr {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }
}
