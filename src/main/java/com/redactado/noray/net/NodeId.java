package com.redactado.noray.net;

import java.util.Objects;

public record NodeId(String hex) {
    public NodeId {
        Objects.requireNonNull(hex, "hex");
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("NodeId must be 64 lowercase hex chars");
        }
    }

    @Override
    public String toString() {
        return hex;
    }
}
