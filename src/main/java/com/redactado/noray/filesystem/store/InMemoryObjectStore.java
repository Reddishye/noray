package com.redactado.noray.filesystem.store;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryObjectStore implements ObjectStore {
    private final ConcurrentHashMap<ObjectId, byte[]> storage = new ConcurrentHashMap<>();

    @Override
    public PutResult put(byte[] data) throws FsException {
        Objects.requireNonNull(data, "data");
        ObjectId id = ContentDigest.sha256(data);
        byte[] copy = data.clone();
        byte[] existing = storage.putIfAbsent(id, copy);
        boolean inserted = (existing == null);
        return new PutResult(id, data.length, inserted);
    }

    @Override
    public byte[] get(ObjectId id) throws FsException {
        Objects.requireNonNull(id, "id");
        byte[] data = storage.get(id);
        if (data == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Object not found: " + id);
        }
        ObjectId verified = ContentDigest.sha256(data);
        if (!verified.equals(id)) {
            storage.remove(id);
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "Digest mismatch for object " + id);
        }
        return data.clone();
    }

    @Override
    public boolean has(ObjectId id) {
        Objects.requireNonNull(id, "id");
        return storage.containsKey(id);
    }

    @Override
    public void delete(ObjectId id) throws FsException {
        Objects.requireNonNull(id, "id");
        byte[] removed = storage.remove(id);
        if (removed == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Object not found: " + id);
        }
    }

    @Override
    public long size(ObjectId id) throws FsException {
        Objects.requireNonNull(id, "id");
        byte[] data = storage.get(id);
        if (data == null) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Object not found: " + id);
        }
        return data.length;
    }

    @Override
    public Set<ObjectId> listIds() {
        return Set.copyOf(storage.keySet());
    }
}
