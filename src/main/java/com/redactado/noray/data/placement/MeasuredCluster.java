package com.redactado.noray.data.placement;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.store.PutResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps a cluster so that the speed of every node is <em>measured</em> from the real traffic that goes through it.
 * Once a node has enough samples its {@link StorageNode#speedWeight()} becomes the measured throughput (MB/s,
 * penalised by its failure rate); before that the configured value (also read as MB/s) is used. Placement of hot
 * data and the choice of the replica to read follow, with no benchmark and no operator tuning.
 */
public final class MeasuredCluster implements DataCluster {

    private final DataCluster inner;
    private final Map<String, NodeMetrics> metrics = new ConcurrentHashMap<>();

    public MeasuredCluster(DataCluster inner) {
        this.inner = Objects.requireNonNull(inner, "inner");
    }

    public NodeMetrics metrics(String nodeId) {
        return metrics.computeIfAbsent(nodeId, k -> new NodeMetrics());
    }

    @Override
    public List<StorageNode> nodes() {
        List<StorageNode> out = new ArrayList<>();
        for (StorageNode n : inner.nodes()) {
            out.add(wrap(n));
        }
        return out;
    }

    @Override
    public Optional<StorageNode> find(String id) {
        return inner.find(id).map(this::wrap);
    }

    private StorageNode wrap(StorageNode n) {
        return new MeasuredNode(n, metrics(n.id()));
    }

    private static final class MeasuredNode implements StorageNode {
        private final StorageNode node;
        private final NodeMetrics metrics;
        private final ObjectStore store;

        MeasuredNode(StorageNode node, NodeMetrics metrics) {
            this.node = node;
            this.metrics = metrics;
            this.store = new MeasuredStore(node.store(), metrics);
        }

        @Override public String id() { return node.id(); }
        @Override public double capacityWeight() { return node.capacityWeight(); }
        @Override public double speedWeight() { return metrics.effectiveSpeed(node.speedWeight()); }
        @Override public boolean accepts(Tier tier) { return node.accepts(tier); }
        @Override public String domain() { return node.domain(); }
        @Override public boolean available() { return node.available(); }
        @Override public ObjectStore store() { return store; }
    }

    private static final class MeasuredStore implements ObjectStore {
        private final ObjectStore inner;
        private final NodeMetrics metrics;

        MeasuredStore(ObjectStore inner, NodeMetrics metrics) {
            this.inner = inner;
            this.metrics = metrics;
        }

        @Override
        public PutResult put(byte[] data) throws FsException {
            long t0 = System.nanoTime();
            try {
                PutResult r = inner.put(data);
                metrics.record(data.length, System.nanoTime() - t0, true);
                return r;
            } catch (RuntimeException e) {
                metrics.record(0, 0, false);
                throw e;
            }
        }

        @Override
        public byte[] get(ObjectId id) throws FsException {
            long t0 = System.nanoTime();
            try {
                byte[] d = inner.get(id);
                metrics.record(d.length, System.nanoTime() - t0, true);
                return d;
            } catch (FsException e) {
                if (e.code() != FsErrorCode.NOT_FOUND) { // a missing blob is not the node's speed problem
                    metrics.record(0, 0, false);
                }
                throw e;
            } catch (RuntimeException e) {
                metrics.record(0, 0, false);
                throw e;
            }
        }

        @Override public boolean has(ObjectId id) { return inner.has(id); }
        @Override public void delete(ObjectId id) throws FsException { inner.delete(id); }
        @Override public long size(ObjectId id) throws FsException { return inner.size(id); }
        @Override public Set<ObjectId> listIds() { return inner.listIds(); }
    }
}
