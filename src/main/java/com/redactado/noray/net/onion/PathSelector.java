package com.redactado.noray.net.onion;

import com.redactado.noray.net.NodeId;

import java.util.List;

/** Chooses the relays a message goes through. Replace it to add policies (jurisdictions, trust, latency...). */
@FunctionalInterface
public interface PathSelector {

    /**
     * @param self        the sending node (never part of the path)
     * @param destination the receiving node (never part of the path)
     * @param wanted      how many relays are wanted
     * @param candidates  nodes that may be used
     * @return between 0 and {@code wanted} relays, in the order they are traversed; fewer only if there are not
     *         enough candidates
     */
    List<NodeId> select(NodeId self, NodeId destination, int wanted, java.util.Set<NodeId> candidates);
}
