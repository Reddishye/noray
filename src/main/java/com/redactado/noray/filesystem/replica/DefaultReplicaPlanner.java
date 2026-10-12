package com.redactado.noray.filesystem.replica;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ContentDigest;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.store.PutResult;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeManager;
import com.redactado.noray.filesystem.volume.VolumeState;

import java.util.*;

public class DefaultReplicaPlanner implements ReplicaPlanner {
    private final VolumeManager volumeManager;
    private final QuorumSpec spec;
    private final boolean allowW1;

    public DefaultReplicaPlanner(VolumeManager volumeManager, QuorumSpec spec, boolean allowW1) {
        this.volumeManager = Objects.requireNonNull(volumeManager, "volumeManager");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.allowW1 = allowW1;
    }

    @Override
    public QuorumSpec spec() {
        return spec;
    }

    private List<Volume> resolveMembers(ReplicaGroupId group) {
        String targetGroupId = group.value().toString();
        List<Volume> result = new ArrayList<>();
        for (Volume v : volumeManager.list()) {
            String volGroup = v.metadata().getString("replica-group", "");
            if (targetGroupId.equalsIgnoreCase(volGroup)) {
                if (v.state() == VolumeState.ONLINE || v.state() == VolumeState.DEGRADED) {
                    result.add(v);
                }
            }
        }
        return result;
    }

    @Override
    public PutResult put(ReplicaGroupId group, byte[] data) throws FsException {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(data, "data");

        List<Volume> members = resolveMembers(group);
        int requiredWrites = allowW1 ? 1 : spec.w();
        if (members.size() < requiredWrites) {
            throw new FsException(FsErrorCode.QUORUM_FAILED, "Quorum failed: available members " + members.size() + " < required " + requiredWrites);
        }

        ObjectId id = ContentDigest.sha256(data);
        int successes = 0;
        boolean anyInserted = false;

        for (Volume member : members) {
            try {
                PutResult pr = member.store().put(data);
                successes++;
                if (pr.inserted()) {
                    anyInserted = true;
                }
                if (successes >= spec.w()) {
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        if (successes < requiredWrites) {
            throw new FsException(FsErrorCode.QUORUM_FAILED, "Quorum failed: write successes " + successes + " < required " + requiredWrites);
        }

        return new PutResult(id, data.length, anyInserted);
    }

    @Override
    public byte[] get(ReplicaGroupId group, ObjectId id) throws FsException {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(id, "id");

        List<Volume> members = resolveMembers(group);
        int requiredReads = allowW1 ? 1 : spec.r();
        if (members.size() < requiredReads) {
            throw new FsException(FsErrorCode.QUORUM_FAILED, "Quorum failed: available members " + members.size() + " < required " + requiredReads);
        }

        int notFoundCount = 0;

        // Keep trying members until one returns a verified copy: stopping after r negative answers
        // could miss a replica that still holds the object (e.g. before repair has run).
        for (Volume member : members) {
            try {
                if (member.store().has(id)) {
                    byte[] data = member.store().get(id);
                    if (ContentDigest.sha256(data).equals(id)) {
                        return data;
                    }
                } else {
                    notFoundCount++;
                }
            } catch (Exception ignored) {
            }
        }

        if (notFoundCount == members.size()) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Object " + id + " not found in replica group " + group);
        }

        throw new FsException(FsErrorCode.NOT_FOUND, "Object " + id + " could not be retrieved from quorum in group " + group);
    }

    @Override
    public void repair(ReplicaGroupId group) throws FsException {
        Objects.requireNonNull(group, "group");
        List<Volume> members = resolveMembers(group);
        if (members.isEmpty()) {
            return;
        }

        Set<ObjectId> allIds = new HashSet<>();
        for (Volume member : members) {
            try {
                allIds.addAll(member.store().listIds());
            } catch (Exception ignored) {
            }
        }

        for (ObjectId id : allIds) {
            byte[] payload = null;
            for (Volume member : members) {
                try {
                    if (member.store().has(id)) {
                        payload = member.store().get(id);
                        break;
                    }
                } catch (Exception ignored) {
                }
            }

            if (payload == null) {
                continue;
            }

            for (Volume member : members) {
                try {
                    if (!member.store().has(id)) {
                        member.store().put(payload);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }
}
