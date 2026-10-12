package com.redactado.noray.filesystem.replica;

import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.PutResult;

public interface ReplicaPlanner {
    QuorumSpec spec();
    PutResult put(ReplicaGroupId group, byte[] data) throws FsException;
    byte[] get(ReplicaGroupId group, ObjectId id) throws FsException;
    void repair(ReplicaGroupId group) throws FsException;
}
