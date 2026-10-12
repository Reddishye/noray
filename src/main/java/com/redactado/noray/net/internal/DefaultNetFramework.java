package com.redactado.noray.net.internal;

import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import com.redactado.noray.net.api.NetFramework;
import com.redactado.noray.net.spi.EndpointDriver;
import com.redactado.noray.net.spi.PathPolicy;

import java.util.Objects;
import java.util.Set;

public final class DefaultNetFramework implements NetFramework {

    private final MeshTransport mesh;
    private final EndpointDriverRegistry endpointDriverRegistry;
    private final PathPolicy pathPolicy;

    public DefaultNetFramework(MeshTransport mesh, EndpointDriverRegistry endpointDriverRegistry, PathPolicy pathPolicy) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.endpointDriverRegistry = Objects.requireNonNull(endpointDriverRegistry, "endpointDriverRegistry");
        this.pathPolicy = Objects.requireNonNull(pathPolicy, "pathPolicy");
    }

    @Override public NodeId localId() { return mesh.localId(); }
    @Override public MeshTransport mesh() { return mesh; }
    @Override public void enroll(NodeId peer, PeerCapabilities caps) { mesh.enroll(peer, caps); }
    @Override public void revoke(NodeId peer) { mesh.revoke(peer); }
    @Override public boolean isEnrolled(NodeId peer) { return mesh.isEnrolled(peer); }
    @Override public Set<NodeId> enrolledPeers() { return mesh.peers(); }

    @Override
    public void registerEndpointDriver(EndpointDriver driver) {
        endpointDriverRegistry.register(driver);
    }

    @Override
    public PathPolicy pathPolicy() {
        return pathPolicy;
    }
}