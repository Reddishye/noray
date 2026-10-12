package com.redactado.noray.net.event;

import com.redactado.noray.kernel.api.Event;
import com.redactado.noray.net.NodeId;

import java.time.Instant;
import java.util.Objects;

public record PeerDown(NodeId peer, String reason, Instant timestamp) implements Event {
    public PeerDown {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public PeerDown(NodeId peer, String reason) {
        this(peer, reason, Instant.now());
    }
}
