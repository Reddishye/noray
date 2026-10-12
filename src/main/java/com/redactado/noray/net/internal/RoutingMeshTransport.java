package com.redactado.noray.net.internal;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import com.redactado.noray.net.enroll.EnrollmentRecord;
import com.redactado.noray.net.enroll.EnrollmentStore;
import com.redactado.noray.net.overlay.UdpOverlay;
import com.redactado.noray.net.spi.EndpointDriver;
import com.redactado.noray.net.spi.EstablishedPath;
import com.redactado.noray.net.spi.PathHint;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public final class RoutingMeshTransport implements MeshTransport, AutoCloseable {

    private final MeshTransport inner;
    private final EnrollmentStore store;
    private final EndpointDriverRegistry drivers;
    private final UdpOverlay overlay;
    private final Map<MeshChannel, CopyOnWriteArrayList<BiConsumer<NodeId, byte[]>>> handlers = new ConcurrentHashMap<>();

    public RoutingMeshTransport(
            MeshTransport inner,
            EnrollmentStore store,
            EndpointDriverRegistry drivers,
            UdpOverlay overlay
    ) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.store = Objects.requireNonNull(store, "store");
        this.drivers = Objects.requireNonNull(drivers, "drivers");
        this.overlay = overlay;
        if (overlay != null) {
            overlay.setReceiver(this::onOverlayPlain);
        }
    }

    private void onOverlayPlain(NodeId from, byte[] innerBytes) {
        if (innerBytes.length < 1) return;
        int ord = innerBytes[0] & 0xff;
        MeshChannel ch = MeshChannel.fromWireId(ord);
        if (ch == null) return;
        byte[] payload = java.util.Arrays.copyOfRange(innerBytes, 1, innerBytes.length);
        fire(from, ch, payload);
    }

    private void fire(NodeId from, MeshChannel ch, byte[] payload) {
        CopyOnWriteArrayList<BiConsumer<NodeId, byte[]>> list = handlers.get(ch);
        if (list == null) return;
        for (BiConsumer<NodeId, byte[]> h : list) {
            h.accept(from, payload);
        }
    }

    @Override public NodeId localId() { return inner.localId(); }
    @Override public void enroll(NodeId peer, PeerCapabilities caps) { inner.enroll(peer, caps); }
    @Override public void revoke(NodeId peer) { inner.revoke(peer); }
    @Override public boolean isEnrolled(NodeId peer) { return inner.isEnrolled(peer); }
    @Override public Set<NodeId> peers() { return inner.peers(); }

    @Override
    public void subscribe(MeshChannel channel, BiConsumer<NodeId, byte[]> handler) {
        handlers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(handler);
        inner.subscribe(channel, handler);
    }

    @Override
    public void unsubscribe(MeshChannel channel, BiConsumer<NodeId, byte[]> handler) {
        CopyOnWriteArrayList<BiConsumer<NodeId, byte[]>> list = handlers.get(channel);
        if (list != null) {
            list.remove(handler);
        }
        inner.unsubscribe(channel, handler);
    }

    @Override
    public void send(NodeId peer, MeshChannel channel, byte[] payload) throws FsException {
        try {
            inner.send(peer, channel, payload);
        } catch (FsException e) {
            if (e.code() != FsErrorCode.DENIED || !inner.isEnrolled(peer)) {
                throw e;
            }
            EnrollmentRecord rec = store.find(peer).orElseThrow(() -> e);
            if (rec.introHint().isEmpty()) {
                throw e;
            }
            PathHint hint = new PathHint("intro", rec.introHint(), Map.of());
            EndpointDriver driver = drivers.find(hint).orElseThrow(() -> e);
            EstablishedPath path = driver.open(peer, hint);
            path.write(NoiseConcat(channel, payload));
        }
    }

    private static byte[] NoiseConcat(MeshChannel channel, byte[] payload) {
        byte[] out = new byte[1 + payload.length];
        out[0] = (byte) channel.wireId();
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    @Override
    public void close() {
        if (overlay != null) overlay.close();
        if (inner instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
}
