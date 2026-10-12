package com.redactado.noray.identity;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a peer may do on another node's blob store. Granted by certificates, never assumed.
 * Destructive and enumerating operations are separate from the ones replication needs, so a node that
 * only stores and serves data can never wipe or list its peers.
 */
public enum Op {
    /** Store a blob. */
    PUT,
    /** Read a blob. */
    GET,
    /** Ask whether a blob exists or how large it is. */
    HAS,
    /** Delete a blob. */
    DELETE,
    /** Enumerate every blob id on the node. */
    LIST;

    public static int mask(Set<Op> ops) {
        int m = 0;
        for (Op op : ops) {
            m |= 1 << op.ordinal();
        }
        return m;
    }

    public static Set<Op> fromMask(int mask) {
        EnumSet<Op> ops = EnumSet.noneOf(Op.class);
        for (Op op : values()) {
            if ((mask & (1 << op.ordinal())) != 0) {
                ops.add(op);
            }
        }
        if ((mask >>> values().length) != 0) {
            throw new IllegalArgumentException("unknown operation bits in " + mask);
        }
        return ops;
    }
}
