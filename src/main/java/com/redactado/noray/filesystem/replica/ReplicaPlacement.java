package com.redactado.noray.filesystem.replica;

import com.redactado.noray.filesystem.volume.VolumeId;

import java.util.List;
import java.util.Objects;

public record ReplicaPlacement(ReplicaGroupId group, List<VolumeId> members) {
    public ReplicaPlacement {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(members, "members");
        members = List.copyOf(members);
    }
}
