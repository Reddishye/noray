package com.redactado.noray.net.onion;

import com.redactado.noray.net.NodeId;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Uniformly random distinct relays. */
public final class RandomPathSelector implements PathSelector {

    private final SecureRandom random;

    public RandomPathSelector() {
        this(new SecureRandom());
    }

    public RandomPathSelector(SecureRandom random) {
        this.random = random;
    }

    @Override
    public List<NodeId> select(NodeId self, NodeId destination, int wanted, Set<NodeId> candidates) {
        List<NodeId> pool = new ArrayList<>();
        for (NodeId n : candidates) {
            if (!n.equals(self) && !n.equals(destination)) {
                pool.add(n);
            }
        }
        pool.sort(java.util.Comparator.comparing(NodeId::hex)); // deterministic input, random choice
        Collections.shuffle(pool, random);
        return new ArrayList<>(pool.subList(0, Math.min(wanted, pool.size())));
    }
}
