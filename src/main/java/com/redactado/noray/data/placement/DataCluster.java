package com.redactado.noray.data.placement;

import java.util.List;
import java.util.Optional;

/** The set of nodes the data engine can use. */
public interface DataCluster {

    /** All known nodes, including unavailable ones. */
    List<StorageNode> nodes();

    default Optional<StorageNode> find(String id) {
        return nodes().stream().filter(n -> n.id().equals(id)).findFirst();
    }

    static DataCluster of(List<? extends StorageNode> nodes) {
        List<StorageNode> copy = List.copyOf(nodes);
        return () -> copy;
    }
}
