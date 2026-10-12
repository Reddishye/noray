package com.redactado.noray.filesystem.store;

import java.util.Objects;

public record ObjectId(String hex) {
    public ObjectId {
        Objects.requireNonNull(hex, "hex");
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ObjectId must be 64 lowercase hex chars");
        }
    }

    @Override
    public String toString() {
        return hex;
    }
}
