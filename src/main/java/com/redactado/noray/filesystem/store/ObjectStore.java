package com.redactado.noray.filesystem.store;

import com.redactado.noray.filesystem.api.FsException;

import java.util.Set;

public interface ObjectStore {
    PutResult put(byte[] data) throws FsException;
    byte[] get(ObjectId id) throws FsException;          // NOT_FOUND or DIGEST_MISMATCH
    boolean has(ObjectId id);
    void delete(ObjectId id) throws FsException;         // missing → NOT_FOUND
    long size(ObjectId id) throws FsException;
    Set<ObjectId> listIds();
}
