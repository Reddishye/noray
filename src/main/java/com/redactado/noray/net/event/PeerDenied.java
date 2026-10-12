package com.redactado.noray.net.event;

import com.redactado.noray.kernel.api.Event;
import com.redactado.noray.net.NodeId;

import java.time.Instant;
import java.util.Objects;

public record PeerDenied(NodeId peer, String reason, Instant timestamp) implements Event {
    public PeerDenied {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public PeerDenied(NodeId peer, String reason) {
        this(peer, reason, Instant.now());
    }
}
