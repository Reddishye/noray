package com.redactado.noray.net.overlay;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.spi.EndpointDriver;
import com.redactado.noray.net.spi.EstablishedPath;
import com.redactado.noray.net.spi.PathHint;

import java.net.InetSocketAddress;
import java.util.Objects;

public final class NoiseUdpEndpointDriver implements EndpointDriver {

    private final UdpOverlay overlay;

    public NoiseUdpEndpointDriver(UdpOverlay overlay) {
        this.overlay = Objects.requireNonNull(overlay, "overlay");
    }

    @Override
    public String name() {
        return "noise-udp";
    }

    @Override
    public boolean supports(PathHint hint) {
        String s = hint.scheme();
        return "intro".equals(s) || "noise-udp".equals(s);
    }

    @Override
    public EstablishedPath open(NodeId peer, PathHint hint) throws FsException {
        // the enrolled hint may list several ways in (host:port, relay:<node>): the overlay tries them in order
        overlay.ensureInitiator(peer, (InetSocketAddress) null);
        return new EstablishedPath() {
            @Override public NodeId peer() { return peer; }
            @Override public void write(byte[] frame) {
                if (frame.length < 1) throw new FsException(FsErrorCode.INTERNAL, "empty frame");
                int ord = frame[0] & 0xff;
                MeshChannel ch = MeshChannel.fromWireId(ord);
                if (ch == null) throw new FsException(FsErrorCode.INTERNAL, "unknown channel id: " + ord);
                byte[] payload = java.util.Arrays.copyOfRange(frame, 1, frame.length);
                overlay.sendPlain(peer, ch, payload);
            }
            @Override public void close() {}
        };
    }
}
