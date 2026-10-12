package com.redactado.noray.data.placement;

import com.redactado.noray.filesystem.store.ObjectStore;

/** A place where blobs can be stored: a local volume, a remote peer, ... */
public interface StorageNode {

    /** Stable identifier, recorded in manifests (no '|', ':', ',' or newlines). */
    String id();

    /** Relative storage capacity: a node with 2 receives about twice the COLD data of a node with 1. */
    double capacityWeight();

    /** Relative read/write speed: a node with 2 receives about twice the HOT data of a node with 1. */
    double speedWeight();

    /** Whether this node may hold data of the given class. Defaults to everything. */
    default boolean accepts(Tier tier) {
        return true;
    }

    /** The weight that drives placement of data of the given class. */
    default double weightFor(Tier tier) {
        return tier == Tier.HOT ? speedWeight() : capacityWeight();
    }

    /**
     * Failure domain (operator, rack, network, jurisdiction...). Pieces of the same chunk are spread over
     * different domains whenever possible, so that one seizure or outage cannot take them all.
     */
    String domain();

    boolean available();

    ObjectStore store();
}
