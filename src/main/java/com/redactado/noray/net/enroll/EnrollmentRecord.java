package com.redactado.noray.net.enroll;

import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;

import java.util.Objects;
import java.util.Set;

public record EnrollmentRecord(
        NodeId nodeId,
        boolean fsStore,
        boolean repo,
        Set<String> tags,
        String introHint,
        String x25519Pub,
        String mlkemPub
) {
    public EnrollmentRecord {
        Objects.requireNonNull(nodeId, "nodeId");
        tags = Set.copyOf(Objects.requireNonNullElse(tags, Set.of()));
        introHint = introHint == null ? "" : introHint;
        x25519Pub = x25519Pub == null ? "" : x25519Pub;
        mlkemPub = mlkemPub == null ? "" : mlkemPub;
    }

    public PeerCapabilities capabilities() {
        return new PeerCapabilities(fsStore, repo, tags);
    }
}
