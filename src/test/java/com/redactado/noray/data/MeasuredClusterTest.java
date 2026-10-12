package com.redactado.noray.data;

import com.redactado.noray.data.manifest.FileRef;
import com.redactado.noray.data.placement.DataCluster;
import com.redactado.noray.data.placement.MeasuredCluster;
import com.redactado.noray.data.placement.NodeMetrics;
import com.redactado.noray.data.placement.SimpleNode;
import com.redactado.noray.data.placement.StorageNode;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.store.PutResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MeasuredClusterTest {

    /** A store with artificial latency per operation. */
    static final class Slow implements ObjectStore {
        final InMemoryObjectStore inner = new InMemoryObjectStore();
        final long millis;
        volatile boolean failing;

        Slow(long millis) { this.millis = millis; }

        private void work() {
            if (failing) throw new FsException(FsErrorCode.INTERNAL, "broken disk");
            try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        @Override public PutResult put(byte[] d) { work(); return inner.put(d); }
        @Override public byte[] get(ObjectId id) { work(); return inner.get(id); }
        @Override public boolean has(ObjectId id) { return inner.has(id); }
        @Override public void delete(ObjectId id) { inner.delete(id); }
        @Override public long size(ObjectId id) { return inner.size(id); }
        @Override public Set<ObjectId> listIds() { return inner.listIds(); }
    }

    @Test
    void metricsUseConfiguredSpeedUntilWarmedUpThenTheMeasurement() {
        NodeMetrics m = new NodeMetrics();
        assertEquals(100.0, m.effectiveSpeed(100.0));
        for (int i = 0; i < NodeMetrics.WARMUP_SAMPLES - 1; i++) m.record(1 << 20, 100_000_000L, true); // 10 MB/s
        assertEquals(100.0, m.effectiveSpeed(100.0), "still warming up");
        m.record(1 << 20, 100_000_000L, true);
        assertEquals(10.0, m.effectiveSpeed(100.0), 0.01);
    }

    @Test
    void failuresReduceTheEffectiveSpeed() {
        NodeMetrics healthy = new NodeMetrics();
        NodeMetrics flaky = new NodeMetrics();
        for (int i = 0; i < 40; i++) {
            healthy.record(1 << 20, 100_000_000L, true);
            flaky.record(1 << 20, 100_000_000L, i % 2 == 0);
        }
        assertTrue(flaky.effectiveSpeed(1) < healthy.effectiveSpeed(1) * 0.8);
        assertTrue(flaky.failureRate() > 0.2);
    }

    @Test
    void slowNodeEarnsALowerWeightFromRealTraffic() {
        Slow slowStore = new Slow(20);
        Slow fastStore = new Slow(0);
        SimpleNode slow = new SimpleNode("slow", slowStore);
        SimpleNode fast = new SimpleNode("fast", fastStore);
        MeasuredCluster cluster = new MeasuredCluster(DataCluster.of(List.of(slow, fast)));
        StorageNode slowNode = cluster.find("slow").orElseThrow();
        StorageNode fastNode = cluster.find("fast").orElseThrow();
        assertEquals(1.0, slowNode.speedWeight(), "configured value before any traffic");

        byte[] block = new byte[256 * 1024];
        for (int i = 0; i < NodeMetrics.WARMUP_SAMPLES + 2; i++) {
            slowNode.store().put(block);
            fastNode.store().put(block);
        }
        assertTrue(fastNode.speedWeight() > slowNode.speedWeight() * 3,
                "fast=" + fastNode.speedWeight() + " slow=" + slowNode.speedWeight());
        // wrappers share the same metrics no matter how often nodes() is called
        assertEquals(slowNode.speedWeight(), cluster.find("slow").orElseThrow().speedWeight());
    }

    @Test
    void hotDataAndReadsFollowMeasuredSpeed() {
        List<SimpleNode> raw = new ArrayList<>();
        List<Slow> stores = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Slow s = new Slow(i == 0 ? 8 : 0); // node 0 is slow
            stores.add(s);
            raw.add(new SimpleNode("n" + i, s));
        }
        MeasuredCluster cluster = new MeasuredCluster(DataCluster.of(new ArrayList<>(raw)));
        StoragePolicy hot = StoragePolicy.single().withChunkSize(1024);
        DataEngine engine = new DataEngine(cluster, hot);

        byte[] warm = new byte[1024 * 40];
        new Random(1).nextBytes(warm);
        engine.put(warm); // generates the samples
        stores.forEach(s -> s.inner.listIds().forEach(s.inner::delete));

        byte[] data = new byte[1024 * 200];
        new Random(2).nextBytes(data);
        engine.put(data);
        int onSlow = stores.get(0).inner.listIds().size();
        int onOthers = 0; // measured speeds of fast nodes differ wildly, so compare against all of them together
        for (int i = 1; i < stores.size(); i++) onOthers += stores.get(i).inner.listIds().size();
        assertTrue(onSlow * 10 <= onOthers, "slow node holds " + onSlow + ", the fast ones " + onOthers);
    }

    @Test
    void readsPreferTheFasterReplicaWithoutIgnoringTheSlowOne() {
        Slow slowStore = new Slow(15);
        Slow fastStore = new Slow(0);
        List<SimpleNode> raw = List.of(new SimpleNode("slow", slowStore), new SimpleNode("fast", fastStore));
        MeasuredCluster cluster = new MeasuredCluster(DataCluster.of(raw));
        StoragePolicy twoCopies = new StoragePolicy(1024, List.of(new StoragePolicy.Variant(
                com.redactado.noray.data.placement.Tier.HOT, 1, com.redactado.noray.data.redundancy.Redundancy.replicas(2))),
                2, 256, true);
        DataEngine engine = new DataEngine(cluster, twoCopies);
        byte[] data = new byte[1024 * 30];
        new Random(3).nextBytes(data);
        FileRef ref = engine.put(data);
        for (int i = 0; i < 3; i++) assertArrayEquals(data, engine.getAll(ref)); // feed the metrics
        assertTrue(cluster.find("fast").orElseThrow().speedWeight() > cluster.find("slow").orElseThrow().speedWeight());

        slowStore.failing = false;
        fastStore.failing = true; // the fast node dies: the slow replica must still serve
        assertArrayEquals(data, engine.getAll(ref));
    }
}
