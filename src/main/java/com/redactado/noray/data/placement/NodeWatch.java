package com.redactado.noray.data.placement;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells a node that is briefly unreachable (a reboot, a network blip) from one that is gone for good, so that
 * repair does not copy terabytes around every time a peer restarts. A node is <em>lost</em> once it has been
 * seen unavailable continuously for longer than the grace period, or when it disappeared from the cluster.
 */
public final class NodeWatch {

    private final Clock clock;
    private final long graceMillis;
    private final Map<String, Long> downSince = new ConcurrentHashMap<>();

    public NodeWatch(Clock clock, Duration grace) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.graceMillis = grace.toMillis();
    }

    /** Call regularly (the maintenance cycle does). */
    public void observe(DataCluster cluster) {
        long now = clock.millis();
        for (StorageNode n : cluster.nodes()) {
            if (n.available()) {
                downSince.remove(n.id());
            } else {
                downSince.putIfAbsent(n.id(), now);
            }
        }
    }

    public boolean isLost(String nodeId, DataCluster cluster) {
        if (cluster.find(nodeId).isEmpty()) {
            return true;
        }
        Long since = downSince.get(nodeId);
        return since != null && clock.millis() - since >= graceMillis;
    }
}
