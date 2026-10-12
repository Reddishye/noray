package com.redactado.noray.net;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.event.PeerDenied;
import com.redactado.noray.net.event.PeerDown;
import com.redactado.noray.net.event.PeerUp;
import com.redactado.noray.kernel.api.EventBus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * In-memory mesh transport. Uses an InMemoryMeshFabric to route
 * messages between transports in the same JVM for testing purposes.
 * No sockets, no crypto.
 */
public final class InMemoryMeshTransport implements MeshTransport {
    private final NodeId localId;
    private final EventBus eventBus;
    private final InMemoryMeshFabric fabric;
    private final boolean enrollmentRequired;

    /** Enrolled peers: nodeId → capabilities */
    private final ConcurrentHashMap<NodeId, PeerCapabilities> enrolledPeers = new ConcurrentHashMap<>();

    /** Subscribed handlers per channel */
    private final ConcurrentHashMap<MeshChannel, List<BiConsumer<NodeId, byte[]>>> handlers = new ConcurrentHashMap<>();

    public InMemoryMeshTransport(NodeId localId, EventBus eventBus, InMemoryMeshFabric fabric, boolean enrollmentRequired) {
        this.localId = Objects.requireNonNull(localId, "localId");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.fabric = Objects.requireNonNull(fabric, "fabric");
        this.enrollmentRequired = enrollmentRequired;
        fabric.register(localId, this);
    }

    @Override
    public NodeId localId() {
        return localId;
    }

    @Override
    public void enroll(NodeId peer, PeerCapabilities caps) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(caps, "caps");
        enrolledPeers.put(peer, caps);
        eventBus.publish(new PeerUp(peer));
    }

    @Override
    public void revoke(NodeId peer) {
        Objects.requireNonNull(peer, "peer");
        PeerCapabilities removed = enrolledPeers.remove(peer);
        if (removed != null) {
            eventBus.publish(new PeerDown(peer, "revoked"));
        }
    }

    @Override
    public boolean isEnrolled(NodeId peer) {
        Objects.requireNonNull(peer, "peer");
        return enrolledPeers.containsKey(peer);
    }

    @Override
    public void send(NodeId peer, MeshChannel channel, byte[] payload) throws FsException {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(payload, "payload");

        if (enrollmentRequired && !isEnrolled(peer)) {
            eventBus.publish(new PeerDenied(peer, "Not enrolled"));
            throw new FsException(FsErrorCode.DENIED, "Peer not enrolled: " + peer);
        }

        InMemoryMeshTransport peerTransport = fabric.get(peer);
        if (peerTransport == null) {
            throw new FsException(FsErrorCode.DENIED, "Peer not found in fabric: " + peer);
        }

        // Check peer has enrolled us too (mutual enrollment)
        if (enrollmentRequired && !peerTransport.isEnrolled(localId)) {
            eventBus.publish(new PeerDenied(peer, "Peer has not enrolled us"));
            throw new FsException(FsErrorCode.DENIED, "Peer has not enrolled local node: " + peer);
        }

        peerTransport.deliver(localId, channel, payload);
    }

    /** Called by the fabric to deliver a message to this transport. */
    void deliver(NodeId from, MeshChannel channel, byte[] payload) {
        List<BiConsumer<NodeId, byte[]>> channelHandlers = handlers.get(channel);
        if (channelHandlers != null) {
            for (BiConsumer<NodeId, byte[]> handler : channelHandlers) {
                try {
                    handler.accept(from, payload);
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void subscribe(MeshChannel channel, BiConsumer<NodeId, byte[]> handler) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(handler, "handler");
        handlers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void unsubscribe(MeshChannel channel, BiConsumer<NodeId, byte[]> handler) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(handler, "handler");
        List<BiConsumer<NodeId, byte[]>> list = handlers.get(channel);
        if (list != null) {
            list.remove(handler);
        }
    }

    @Override
    public Set<NodeId> peers() {
        return Collections.unmodifiableSet(enrolledPeers.keySet());
    }
}
