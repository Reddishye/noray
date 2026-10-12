package com.redactado.noray.net.event;

import com.redactado.noray.kernel.api.Event;
import com.redactado.noray.net.NodeId;

import java.time.Instant;
import java.util.Objects;

public record PeerUp(NodeId peer, Instant timestamp) implements Event {
    public PeerUp {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public PeerUp(NodeId peer) {
        this(peer, Instant.now());
    }
}
