package com.redactado.noray.data.placement;

import com.redactado.noray.filesystem.store.ObjectStore;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plain {@link StorageNode}, mostly for tests and embedding. */
public final class SimpleNode implements StorageNode {
    private final String id;
    private final String domain;
    private final double capacityWeight;
    private final double speedWeight;
    private final Set<Tier> accepts;
    private final ObjectStore store;
    private final AtomicBoolean available = new AtomicBoolean(true);

    public SimpleNode(String id, String domain, double capacityWeight, double speedWeight, Set<Tier> accepts,
                      ObjectStore store) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isEmpty() || id.matches(".*[|:,\\n].*")) {
            throw new IllegalArgumentException("invalid node id: " + id);
        }
        if (!(capacityWeight > 0) || !(speedWeight > 0)) {
            throw new IllegalArgumentException("weights must be > 0");
        }
        this.domain = Objects.requireNonNull(domain, "domain");
        this.capacityWeight = capacityWeight;
        this.speedWeight = speedWeight;
        this.accepts = Set.copyOf(accepts);
        this.store = Objects.requireNonNull(store, "store");
    }

    /** A node that accepts every class, with unit weights; its own failure domain. */
    public SimpleNode(String id, ObjectStore store) {
        this(id, id, 1.0, 1.0, Set.of(Tier.HOT, Tier.COLD), store);
    }

    public SimpleNode(String id, String domain, double capacityWeight, double speedWeight, ObjectStore store) {
        this(id, domain, capacityWeight, speedWeight, Set.of(Tier.HOT, Tier.COLD), store);
    }

    public void setAvailable(boolean value) {
        available.set(value);
    }

    @Override public String id() { return id; }
    @Override public double capacityWeight() { return capacityWeight; }
    @Override public double speedWeight() { return speedWeight; }
    @Override public boolean accepts(Tier tier) { return accepts.contains(tier); }
    @Override public String domain() { return domain; }
    @Override public boolean available() { return available.get(); }
    @Override public ObjectStore store() { return store; }

    @Override
    public String toString() {
        return id + "[cap=" + capacityWeight + ",speed=" + speedWeight + ",d=" + domain + ",accepts=" + accepts + "]";
    }
}
