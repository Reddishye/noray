package com.redactado.noray.data;

import com.redactado.noray.data.manifest.FileRef;
import com.redactado.noray.data.placement.DataCluster;
import com.redactado.noray.data.placement.NodeWatch;
import com.redactado.noray.data.placement.SimpleNode;
import com.redactado.noray.data.placement.Tier;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.store.PutResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepairAndGcTest {

    /** In-memory store that can serve corrupted bytes. */
    static final class Rotting implements ObjectStore {
        final InMemoryObjectStore inner = new InMemoryObjectStore();
        volatile boolean rot;

        @Override public PutResult put(byte[] data) { return inner.put(data); }
        @Override public byte[] get(ObjectId id) {
            byte[] d = inner.get(id);
            if (rot) { d = d.clone(); d[d.length / 2] ^= 0x33; }
            return d;
        }
        @Override public boolean has(ObjectId id) { return inner.has(id); }
        @Override public void delete(ObjectId id) { inner.delete(id); }
        @Override public long size(ObjectId id) { return inner.size(id); }
        @Override public Set<ObjectId> listIds() { return inner.listIds(); }
    }

    private final List<SimpleNode> nodes = new ArrayList<>();
    private final List<Rotting> stores = new ArrayList<>();

    private DataCluster cluster(int n) {
        for (int i = 0; i < n; i++) {
            Rotting r = new Rotting();
            stores.add(r);
            nodes.add(new SimpleNode("n" + i, r));
        }
        return DataCluster.of(new ArrayList<>(nodes));
    }

    private DataCluster dedicated(int hot, int cold) {
        for (int i = 0; i < hot + cold; i++) {
            Rotting r = new Rotting();
            stores.add(r);
            nodes.add(new SimpleNode((i < hot ? "h" : "c") + i, "d" + i, 1, 1,
                    Set.of(i < hot ? Tier.HOT : Tier.COLD), r));
        }
        return DataCluster.of(new ArrayList<>(nodes));
    }

    private static byte[] sample(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        for (int i = 0; i < n / 2; i++) b[i] = (byte) ("repair me ".charAt(i % 10));
        return b;
    }

    private static StoragePolicy small() {
        // 3 manifest copies per class: with the default (5) every one of these 10 nodes would hold a page, and a node that
        // rots forever could then never be "moved off"
        return StoragePolicy.tiered().withChunkSize(2048).withEcMinCipherLen(256).withMetadataReplicas(3);
    }

    @Test
    void healthyFileNeedsNoRepair() {
        DataEngine engine = new DataEngine(cluster(10), small());
        FileRef ref = engine.put(sample(30_000, 1));
        RepairReport r = engine.repair(ref);
        assertFalse(r.changed());
        assertTrue(r.healthy());
        assertSame(ref, r.ref());
        assertTrue(r.chunks() > 10);
    }

    @Test
    void lostNodeIsReplacedAndRedundancyIsRestored() {
        DataEngine engine = new DataEngine(cluster(12), small());
        byte[] data = sample(60_000, 2);
        FileRef ref = engine.put(data);

        String victim = engine.nodesUsed(ref).iterator().next();
        nodes.stream().filter(n -> n.id().equals(victim)).findFirst().orElseThrow().setAvailable(false);

        RepairReport r = engine.repair(ref);
        assertTrue(r.changed());
        assertTrue(r.piecesRebuilt() > 0);
        assertEquals(0, r.chunksLost());
        assertFalse(engine.nodesUsed(r.ref()).contains(victim), "nothing may still point at the dead node");
        assertArrayEquals(data, engine.getAll(r.ref()));

        // the repaired file tolerates the usual number of further losses again
        engine.deleteManifest(ref);
        assertArrayEquals(data, engine.getAll(r.ref()), "old manifest removed, new one stands on its own");
        assertTrue(engine.repair(r.ref()).healthy());
    }

    @Test
    void briefOutagesAreNotRepairedWhenTheNodeIsNotLost() {
        DataEngine engine = new DataEngine(cluster(10), small());
        FileRef ref = engine.put(sample(30_000, 3));
        nodes.get(0).setAvailable(false);
        RepairReport r = engine.repair(ref, false, id -> false); // grace period not over yet
        assertFalse(r.changed());
        assertSame(ref, r.ref());
    }

    @Test
    void nodeWatchSeparatesBlipsFromLosses() {
        DataCluster c = cluster(3);
        long[] now = {0};
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        NodeWatch watch = new NodeWatch(clock, Duration.ofMinutes(10));
        nodes.get(1).setAvailable(false);
        watch.observe(c);
        assertFalse(watch.isLost("n1", c));
        now[0] = Duration.ofMinutes(11).toMillis();
        watch.observe(c);
        assertTrue(watch.isLost("n1", c));
        assertFalse(watch.isLost("n0", c));
        nodes.get(1).setAvailable(true);
        watch.observe(c);
        assertFalse(watch.isLost("n1", c), "back up: no longer lost");
        assertTrue(watch.isLost("ghost", c), "unknown nodes count as gone");
    }

    @Test
    void variantTooDamagedToHealItselfIsRecreatedFromAnotherVariant() {
        DataCluster c = dedicated(3, 13);
        DataEngine engine = new DataEngine(c, small());
        byte[] data = sample(40_000, 4);
        FileRef ref = engine.put(data);

        // 5 of 13 cold nodes die, then 4+4 shards can no longer rebuild themselves everywhere... but hot copies live
        for (int i = 3; i < 8; i++) nodes.get(i).setAvailable(false);
        RepairReport r = engine.repair(ref);
        assertTrue(r.changed());
        assertEquals(0, r.chunksLost());

        // hot nodes vanish: the cold copy of the repaired manifest must stand alone
        for (int i = 0; i < 3; i++) nodes.get(i).setAvailable(false);
        assertArrayEquals(data, engine.getAll(r.ref()));
    }

    @Test
    void unrecoverableChunksAreReportedNotHidden() {
        DataCluster c = dedicated(0, 8);
        DataEngine engine = new DataEngine(c, StoragePolicy.archive(4, 4).withChunkSize(2048).withEcMinCipherLen(256)
                .withMetadataReplicas(8));
        FileRef ref = engine.put(sample(20_000, 5));
        for (int i = 0; i < 5; i++) nodes.get(i).setAvailable(false); // 3 shards left, 4 needed
        RepairReport r = engine.repair(ref);
        assertTrue(r.chunksLost() > 0);
        assertThrows(FsException.class, () -> engine.getAll(r.ref()));
    }

    @Test
    void scrubFindsSilentCorruptionAndMovesDataOffTheBadNode() {
        DataEngine engine = new DataEngine(cluster(10), small());
        byte[] data = sample(50_000, 6);
        FileRef ref = engine.put(data);
        String bad = engine.nodesUsed(ref).iterator().next();
        int idx = Integer.parseInt(bad.substring(1));

        assertTrue(engine.repair(ref).healthy(), "without scrub the node still looks fine");
        stores.get(idx).rot = true;
        RepairReport r = engine.repair(ref, true, id -> false);
        assertTrue(r.changed());
        assertTrue(r.piecesRebuilt() > 0 || r.pagesRestored() > 0);
        assertFalse(engine.nodesUsed(r.ref()).contains(bad));
        assertArrayEquals(data, engine.getAll(r.ref()));
        assertTrue(engine.repair(r.ref(), true, id -> false).healthy());
    }

    @Test
    void lostManifestCopiesAreRestoredWithoutChangingTheReference() {
        DataEngine engine = new DataEngine(cluster(10), small());
        FileRef ref = engine.put(sample(10_000, 7));
        // wipe every manifest copy but one by deleting from all nodes except the first holder we find
        Set<ObjectId> pageIds = new HashSet<>();
        pageIds.add(ref.rootId());
        boolean kept = false;
        for (Rotting s : stores) {
            if (s.inner.has(ref.rootId())) {
                if (!kept) { kept = true; continue; }
                s.inner.delete(ref.rootId());
            }
        }
        RepairReport r = engine.repair(ref);
        assertTrue(r.pagesRestored() > 0);
        assertFalse(r.changed(), "page copies are found by id, so the reference stays valid");
        int copies = 0;
        for (Rotting s : stores) if (s.inner.has(ref.rootId())) copies++;
        assertTrue(copies > 1);
    }

    @Test
    void deleteReleasesEverythingAndDeleteManifestKeepsChunks() {
        DataEngine engine = new DataEngine(cluster(10), small());
        FileRef a = engine.put(sample(30_000, 8));
        int total = stores.stream().mapToInt(s -> s.inner.listIds().size()).sum();
        FileRef b = engine.retier(a, StoragePolicy.archive(4, 4).withChunkSize(2048).withEcMinCipherLen(256));
        engine.deleteManifest(a);
        assertArrayEquals(sample(30_000, 8), engine.getAll(b));
        engine.delete(b);
        assertEquals(0, stores.stream().mapToInt(s -> s.inner.listIds().size()).sum());
        assertTrue(total > 0);
    }

    @Test
    void garbageCollectionRemovesOnlyWhatNoFileNeeds() throws Exception {
        DataEngine engine = new DataEngine(cluster(10), small());
        byte[] keepData = sample(30_000, 9);
        FileRef keep = engine.put(keepData);
        FileRef drop = engine.put(sample(30_000, 10));
        FileRef retiered = engine.retier(drop, StoragePolicy.archive(4, 4).withChunkSize(2048).withEcMinCipherLen(256));
        // `drop` and the pieces it no longer shares with `retiered` are garbage once the namespace forgets them

        Set<ObjectId> live = new HashSet<>(engine.liveBlobs(keep));
        live.addAll(engine.liveBlobs(retiered));

        GcReport protectedRun = engine.collectGarbage(live, Duration.ofHours(1), false);
        assertEquals(0, protectedRun.blobsDeleted(), "freshly written blobs are protected by the grace period");
        assertTrue(protectedRun.blobsProtected() > 0);

        Thread.sleep(5);
        GcReport dry = engine.collectGarbage(live, Duration.ZERO, true);
        assertTrue(dry.blobsDeleted() > 0 && dry.dryRun());
        int before = stores.stream().mapToInt(s -> s.inner.listIds().size()).sum();
        GcReport real = engine.collectGarbage(live, Duration.ZERO, false);
        int after = stores.stream().mapToInt(s -> s.inner.listIds().size()).sum();
        assertEquals(dry.blobsDeleted(), real.blobsDeleted());
        assertEquals(before - real.blobsDeleted(), after);
        assertTrue(real.bytesFreed() > 0);

        Set<ObjectId> remaining = new HashSet<>();
        stores.forEach(s -> remaining.addAll(s.inner.listIds()));
        assertTrue(live.containsAll(remaining), "nothing but live blobs may remain");
        assertArrayEquals(keepData, engine.getAll(keep));
        assertArrayEquals(sample(30_000, 10), engine.getAll(retiered));
    }
}
