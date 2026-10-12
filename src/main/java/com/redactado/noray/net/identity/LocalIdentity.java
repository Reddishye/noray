package com.redactado.noray.net.identity;

import com.redactado.noray.net.NodeId;

import java.util.Objects;

public record LocalIdentity(NodeId nodeId) {
    public LocalIdentity {
        Objects.requireNonNull(nodeId, "nodeId");
    }
}