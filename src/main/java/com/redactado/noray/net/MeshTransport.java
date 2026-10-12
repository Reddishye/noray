package com.redactado.noray.net;

import com.redactado.noray.filesystem.api.FsException;

import java.util.Set;
import java.util.function.BiConsumer;

public interface MeshTransport {
    NodeId localId();
    void enroll(NodeId peer, PeerCapabilities caps);   // operator action
    void revoke(NodeId peer);
    boolean isEnrolled(NodeId peer);
    void send(NodeId peer, MeshChannel channel, byte[] payload) throws FsException; // DENIED if not enrolled
    void subscribe(MeshChannel channel, BiConsumer<NodeId, byte[]> handler);
    /** Removes a handler previously registered with {@link #subscribe}. No-op if it is not registered. */
    void unsubscribe(MeshChannel channel, BiConsumer<NodeId, byte[]> handler);
    Set<NodeId> peers(); // enrolled and PeerUp
}
