package com.redactado.noray.identity;

import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.NodeId;

/** Decides whether an authenticated peer may perform an operation on this node. Must fail closed. */
@FunctionalInterface
public interface AccessPolicy {

    /** @throws FsException with {@code DENIED} when the peer may not do this */
    void check(NodeId peer, Op op) throws FsException;

    /** Everything enrolled is allowed: the pre-certificate behaviour, for tests and unmanaged setups only. */
    static AccessPolicy allowAll() {
        return (peer, op) -> { };
    }
}
