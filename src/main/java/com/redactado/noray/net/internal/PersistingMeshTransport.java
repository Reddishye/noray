package com.redactado.noray.net.internal;

import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import com.redactado.noray.net.enroll.EnrollmentRecord;
import com.redactado.noray.net.enroll.EnrollmentStore;

import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

public final class PersistingMeshTransport implements MeshTransport, AutoCloseable {

    private final MeshTransport inner;
    private final EnrollmentStore store;

    public PersistingMeshTransport(MeshTransport inner, EnrollmentStore store) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.store = Objects.requireNonNull(store, "store");
    }

    public EnrollmentStore store() {
        return store;
    }

    public void loadPersisted() {
        for (EnrollmentRecord rec : store.list()) {
            inner.enroll(rec.nodeId(), rec.capabilities());
        }
    }

    @Override
    public NodeId localId() {
        return inner.localId();
    }

    @Override
    public void enroll(NodeId peer, PeerCapabilities caps) {
        inner.enroll(peer, caps);
        EnrollmentRecord prev = store.find(peer).orElse(null);
        String hint = prev == null ? "" : prev.introHint();
        String x = prev == null ? "" : prev.x25519Pub();
        String k = prev == null ? "" : prev.mlkemPub();
        store.save(new EnrollmentRecord(peer, caps.fsStore(), caps.repo(), caps.tags(), hint, x, k));
    }

    public void enroll(NodeId peer, PeerCapabilities caps, String introHint, String x25519Pub, String mlkemPub) {
        inner.enroll(peer, caps);
        store.save(new EnrollmentRecord(
                peer, caps.fsStore(), caps.repo(), caps.tags(),
                introHint == null ? "" : introHint,
                x25519Pub == null ? "" : x25519Pub,
                mlkemPub == null ? "" : mlkemPub
        ));
    }

    @Override
    public void revoke(NodeId peer) {
        inner.revoke(peer);
        store.delete(peer);
    }

    @Override
    public boolean isEnrolled(NodeId peer) {
        return inner.isEnrolled(peer);
    }

    @Override
    public void send(NodeId peer, MeshChannel channel, byte[] payload) throws FsException {
        inner.send(peer, channel, payload);
    }

    @Override
    public void subscribe(MeshChannel channel, BiConsumer<NodeId, byte[]> handler) {
        inner.subscribe(channel, handler);
    }

    @Override
    public void unsubscribe(MeshChannel channel, BiConsumer<NodeId, byte[]> handler) {
        inner.unsubscribe(channel, handler);
    }

    @Override
    public Set<NodeId> peers() {
        return inner.peers();
    }

    @Override
    public void close() {
        if (inner instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
}
