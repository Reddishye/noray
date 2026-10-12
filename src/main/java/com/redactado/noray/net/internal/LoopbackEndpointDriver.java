package com.redactado.noray.net.internal;

import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.spi.EndpointDriver;
import com.redactado.noray.net.spi.EstablishedPath;
import com.redactado.noray.net.spi.PathHint;

import java.util.Objects;

public final class LoopbackEndpointDriver implements EndpointDriver {

    @Override
    public String name() {
        return "loopback";
    }

    @Override
    public boolean supports(PathHint hint) {
        Objects.requireNonNull(hint, "hint");
        return "loopback".equals(hint.scheme());
    }

    @Override
    public EstablishedPath open(NodeId peer, PathHint hint) {
        return new LoopbackEstablishedPath(peer);
    }

    private static class LoopbackEstablishedPath implements EstablishedPath {
        private final NodeId peer;
        private boolean closed = false;

        LoopbackEstablishedPath(NodeId peer) {
            this.peer = Objects.requireNonNull(peer, "peer");
        }

        @Override
        public NodeId peer() {
            return peer;
        }

        @Override
        public void write(byte[] frame) {
            // no-op for loopback
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}