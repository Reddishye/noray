package com.redactado.noray.filesystem.vfs;

import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.store.ObjectId;

import com.redactado.noray.data.manifest.FileRef;

import java.util.Objects;

public class Inode {
    private final InodeId id;
    private final NodeType type;
    private final VfsPath path;
    private final InodeId parentId;
    private volatile ObjectId data;
    private volatile FileRef ref;
    private volatile String storageClass = "";
    private volatile long size = -1;

    public Inode(InodeId id, NodeType type, VfsPath path, InodeId parentId, ObjectId data) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.path = Objects.requireNonNull(path, "path");
        this.parentId = parentId; // null for root or mount point top
        this.data = data;
    }

    public InodeId id() {
        return id;
    }

    public NodeType type() {
        return type;
    }

    public VfsPath path() {
        return path;
    }

    public InodeId parentId() {
        return parentId;
    }

    public ObjectId data() {
        return data;
    }

    public void setData(ObjectId data) {
        this.data = data;
    }

    /** Handle of the file in the data engine (pool mounts); {@code null} for legacy blob files and directories. */
    public FileRef ref() {
        return ref;
    }

    /** Name of the plain storage policy the file is currently stored with ("" if unknown). */
    public String storageClass() {
        return storageClass;
    }

    /** Content length in bytes; {@code -1} when it was never recorded (legacy journal, nothing written yet). */
    public long size() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    /** True when the file has been given content at least once. */
    public boolean hasContent() {
        return data != null || ref != null;
    }

    public void setFile(FileRef ref, String storageClass) {
        this.ref = ref;
        this.storageClass = storageClass == null ? "" : storageClass;
    }
}
