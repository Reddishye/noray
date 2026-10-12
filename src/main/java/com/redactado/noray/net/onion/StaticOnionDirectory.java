package com.redactado.noray.net.onion;

import com.redactado.noray.net.NodeId;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A directory that is filled by hand (configuration, tests). */
public final class StaticOnionDirectory implements OnionDirectory {

    private final Map<NodeId, byte[]> keys = new ConcurrentHashMap<>();

    public StaticOnionDirectory put(NodeId node, byte[] publicKey) {
        if (publicKey.length != 32) {
            throw new IllegalArgumentException("an onion key is 32 bytes");
        }
        keys.put(node, publicKey.clone());
        return this;
    }

    public void remove(NodeId node) {
        keys.remove(node);
    }

    @Override
    public Optional<byte[]> keyOf(NodeId node) {
        byte[] k = keys.get(node);
        return Optional.ofNullable(k == null ? null : k.clone());
    }

    @Override
    public Set<NodeId> relays() {
        return Set.copyOf(keys.keySet());
    }
}
