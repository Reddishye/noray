package com.redactado.noray.identity;

import java.util.EnumSet;
import java.util.Set;

/** Named sets of operations used when issuing certificates. A certificate stores the operations, not the role. */
public enum Role {
    /** May only read. */
    READER(EnumSet.of(Op.GET, Op.HAS)),
    /** A storage node or an ordinary client: store, read, check. Cannot delete or enumerate. */
    PEER(EnumSet.of(Op.PUT, Op.GET, Op.HAS)),
    /** May also delete and enumerate (garbage collection, maintenance). Keep the number of these very small. */
    OPERATOR(EnumSet.allOf(Op.class));

    private final Set<Op> ops;

    Role(Set<Op> ops) {
        this.ops = Set.copyOf(ops);
    }

    public Set<Op> ops() {
        return ops;
    }

    public static Role parse(String name) {
        return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
