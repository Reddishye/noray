package com.redactado.noray.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared in-JVM fabric connecting multiple InMemoryMeshTransport instances.
 * Create one instance per test; transports register themselves on construction.
 */
public final class InMemoryMeshFabric {
    private final Map<NodeId, InMemoryMeshTransport> transports = new ConcurrentHashMap<>();

    void register(NodeId nodeId, InMemoryMeshTransport transport) {
        transports.put(nodeId, transport);
    }

    void unregister(NodeId nodeId) {
        transports.remove(nodeId);
    }

    InMemoryMeshTransport get(NodeId nodeId) {
        return transports.get(nodeId);
    }
}
