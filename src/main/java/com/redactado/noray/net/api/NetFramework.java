package com.redactado.noray.net.api;

import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import com.redactado.noray.net.spi.EndpointDriver;
import com.redactado.noray.net.spi.PathPolicy;

import java.util.Set;

/**
 * Public facade of the noray.net mini-framework (Linux net core analog).
 * Other modules CALL this for mesh. They do not import InMemoryMeshFabric.
 */
public interface NetFramework {

    NodeId localId();

    MeshTransport mesh();

    void enroll(NodeId peer, PeerCapabilities caps);

    void revoke(NodeId peer);

    boolean isEnrolled(NodeId peer);

    Set<NodeId> enrolledPeers();

    void registerEndpointDriver(EndpointDriver driver);

    PathPolicy pathPolicy();
}