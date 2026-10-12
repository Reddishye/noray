package com.redactado.noray.filesystem.replica;

import java.util.Objects;
import java.util.UUID;

public record ReplicaGroupId(UUID value) {
    public ReplicaGroupId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
