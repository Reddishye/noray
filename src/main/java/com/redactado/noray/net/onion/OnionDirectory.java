package com.redactado.noray.net.onion;

import com.redactado.noray.net.NodeId;

import java.util.Optional;
import java.util.Set;

/**
 * Where the onion layer learns which nodes can act as hops or destinations. It is a plug-in point: a mesh that
 * exchanges keys itself ({@link MeshOnionTransport}), a static list ({@link StaticOnionDirectory}), a directory
 * service, ...
 */
public interface OnionDirectory {

    /** The onion public key (32 bytes) of {@code node}, if known. */
    Optional<byte[]> keyOf(NodeId node);

    /**
     * Nodes that can be used as relays. Every one of them is assumed to be able to hand a cell to any other
     * (a meshed set of enrolled nodes); a node that is not reachable simply loses the cells sent through it.
     */
    Set<NodeId> relays();
}
