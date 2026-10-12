package com.redactado.noray.data;

import com.redactado.noray.data.manifest.FileRef;
import com.redactado.noray.data.placement.DataCluster;
import com.redactado.noray.data.placement.SimpleNode;
import com.redactado.noray.data.placement.Tier;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.store.PutResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DataEngineTest {

    /** Store that can be told to corrupt reads or refuse writes. */
    static final class FlakyStore implements ObjectStore {
        private final InMemoryObjectStore inner = new InMemoryObjectStore();
        volatile boolean corruptReads;
        volatile boolean failWrites;

        @Override public boolean has(ObjectId id) { return inner.has(id); }
        @Override public void delete(ObjectId id) { inner.delete(id); }
        @Override public long size(ObjectId id) { return inner.size(id); }
        @Override public Set<ObjectId> listIds() { return inner.listIds(); }

        @Override
        public PutResult put(byte[] data) throws FsException {
            if (failWrites) throw new FsException(FsErrorCode.INTERNAL, "disk full");
            return inner.put(data);
        }

        @Override
        public byte[] get(ObjectId id) throws FsException {
            byte[] d = inner.get(id);
            if (corruptReads) {
                d = d.clone();
                d[d.length / 2] ^= 0x5a;
            }
            return d;
        }
    }

    private final List<SimpleNode> hot = new ArrayList<>();
    private final List<SimpleNode> cold = new ArrayList<>();
    private final List<FlakyStore> hotStores = new ArrayList<>();
    private final List<FlakyStore> coldStores = new ArrayList<>();

    private DataCluster cluster(int hotN, int coldN) {
        List<SimpleNode> all = new ArrayList<>();
        for (int i = 0; i < hotN; i++) {
            FlakyStore s = new FlakyStore();
            hotStores.add(s);
            SimpleNode n = new SimpleNode("hot" + i, "hd" + i, 1, 1, Set.of(Tier.HOT), s);
            hot.add(n);
            all.add(n);
        }
        for (int i = 0; i < coldN; i++) {
            FlakyStore s = new FlakyStore();
            coldStores.add(s);
            SimpleNode n = new SimpleNode("cold" + i, "cd" + i, 1, 1, Set.of(Tier.COLD), s);
            cold.add(n);
            all.add(n);
        }
        return DataCluster.of(new ArrayList<>(all));
    }

    /** Nodes that accept every class of data: temperature belongs to the data, not to the node. */
    private DataCluster mixed(int n, double[] capacity, double[] speed) {
        List<SimpleNode> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            FlakyStore s = new FlakyStore();
            allStores.add(s);
            SimpleNode node = new SimpleNode("m" + i, "md" + i, capacity == null ? 1 : capacity[i],
                    speed == null ? 1 : speed[i], s);
            mixedNodes.add(node);
            all.add(node);
        }
        return DataCluster.of(new ArrayList<>(all));
    }

    private final List<FlakyStore> allStores = new ArrayList<>();
    private final List<SimpleNode> mixedNodes = new ArrayList<>();

    private int totalBlobs() {
        return allStores.stream().mapToInt(s -> s.listIds().size()).sum();
    }

    private static byte[] sample(int len, long seed) {
        // half text-like (compressible), half random
        byte[] b = new byte[len];
        Random r = new Random(seed);
        for (int i = 0; i < len; i++) {
            b[i] = i < len / 2 ? (byte) ("noray data engine ".charAt(i % 18)) : (byte) r.nextInt();
        }
        return b;
    }

    private static StoragePolicy smallTiered() {
        return StoragePolicy.tiered().withChunkSize(4096).withEcMinCipherLen(512);
    }

    @Test
    void roundTripTiered() {
        DataCluster c = cluster(3, 8);
        DataEngine engine = new DataEngine(c, smallTiered());
        byte[] data = sample(50_000, 1);
        FileRef ref = engine.put(data);
        assertEquals(data.length, engine.size(ref));
        assertArrayEquals(data, engine.getAll(ref));
        assertTrue(coldStores.stream().mapToInt(s -> s.listIds().size()).sum() > 0);
        assertTrue(hotStores.stream().mapToInt(s -> s.listIds().size()).sum() > 0);
    }

    @Test
    void storedBlobsNeverContainPlaintext() {
        DataCluster c = cluster(2, 8);
        DataEngine engine = new DataEngine(c, smallTiered());
        byte[] marker = "TOP-SECRET-MARKER-TOP-SECRET-MARKER".getBytes();
        byte[] data = new byte[20_000];
        for (int i = 0; i < data.length; i++) data[i] = marker[i % marker.length];
        engine.put(data);
        for (var s : concat(hotStores, coldStores)) {
            for (ObjectId id : s.listIds()) {
                String raw = new String(s.get(id), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(raw.contains("TOP-SECRET"));
            }
        }
    }

    @SafeVarargs
    private static <T> List<T> concat(List<? extends T>... lists) {
        List<T> out = new ArrayList<>();
        for (var l : lists) out.addAll(l);
        return out;
    }

    @Test
    void emptyAndTinyFiles() {
        DataEngine engine = new DataEngine(cluster(2, 8), smallTiered());
        FileRef empty = engine.put(new byte[0]);
        assertEquals(0, engine.size(empty));
        assertEquals(0, engine.getAll(empty).length);
        FileRef one = engine.put(new byte[]{42});
        assertArrayEquals(new byte[]{42}, engine.getAll(one));
    }

    @Test
    void toleratesColdNodeLossWhenHotIsGone() {
        DataCluster c = cluster(2, 8);
        DataEngine engine = new DataEngine(c, smallTiered());
        byte[] data = sample(30_000, 2);
        FileRef ref = engine.put(data);

        hot.forEach(n -> n.setAvailable(false)); // principal nodes down: must be served from cold
        assertArrayEquals(data, engine.getAll(ref));
    }

    @Test
    void coldTierSurvivesMLosses() {
        // 4+4 erasure coding on 8 nodes: up to 4 may disappear (manifests are copied to every node here)
        DataCluster c = cluster(0, 8);
        DataEngine engine = new DataEngine(c, StoragePolicy.archive(4, 4).withChunkSize(4096).withEcMinCipherLen(512).withMetadataReplicas(8));
        byte[] data = sample(40_000, 3);
        FileRef ref = engine.put(data);
        assertArrayEquals(data, engine.getAll(ref));
        for (int i = 0; i < 4; i++) cold.get(i).setAvailable(false);
        assertArrayEquals(data, engine.getAll(ref));
    }

    @Test
    void failsWhenTooManyColdNodesAreLostAndNoHot() {
        DataCluster c = cluster(0, 8);
        DataEngine engine = new DataEngine(c, StoragePolicy.archive(4, 4).withChunkSize(4096).withEcMinCipherLen(512).withMetadataReplicas(8));
        FileRef ref = engine.put(sample(40_000, 4));
        for (int i = 0; i < 5; i++) cold.get(i).setAvailable(false);
        assertThrows(FsException.class, () -> engine.getAll(ref));
    }

    @Test
    void corruptHotBlobsFallBackToCold() {
        DataEngine engine = new DataEngine(cluster(2, 8), smallTiered());
        byte[] data = sample(30_000, 5);
        FileRef ref = engine.put(data);
        hotStores.forEach(s -> s.corruptReads = true); // hash check rejects them, cold variant serves
        // manifests live on the 2 hot nodes: keep one of them honest so the pages stay readable
        hotStores.get(0).corruptReads = false;
        assertArrayEquals(data, engine.getAll(ref));
    }

    @Test
    void corruptColdShardsAreDetectedAndRebuilt() {
        DataEngine engine = new DataEngine(cluster(0, 8), StoragePolicy.archive(4, 4).withChunkSize(4096).withEcMinCipherLen(512).withMetadataReplicas(8));
        byte[] data = sample(30_000, 6);
        FileRef ref = engine.put(data);
        for (int i = 0; i < 3; i++) coldStores.get(i).corruptReads = true;
        assertArrayEquals(data, engine.getAll(ref));
    }

    @Test
    void multiLevelTreeWithRangeReads() {
        DataEngine engine = new DataEngine(cluster(2, 8), smallTiered().withChunkSize(1024), 4);
        byte[] data = sample(1024 * 70 + 123, 7); // 71 chunks, fanout 4 → 4 levels
        FileRef ref = engine.put(new ByteArrayInputStream(data));
        assertEquals(data.length, engine.size(ref));
        assertArrayEquals(data, engine.getAll(ref));

        Random r = new Random(9);
        for (int i = 0; i < 50; i++) {
            long off = r.nextInt(data.length);
            int len = r.nextInt(5000);
            byte[] expected = Arrays.copyOfRange(data, (int) off, (int) Math.min(data.length, off + len));
            assertArrayEquals(expected, engine.read(ref, off, len), "off=" + off + " len=" + len);
        }
        assertEquals(0, engine.read(ref, data.length + 10, 5).length);
    }

    @Test
    void exactFanoutBoundariesProduceValidTrees() {
        DataEngine engine = new DataEngine(cluster(2, 8), smallTiered().withChunkSize(1024), 3);
        for (int chunks : new int[]{1, 2, 3, 4, 5, 9, 10, 27, 28}) {
            byte[] data = sample(1024 * chunks, chunks);
            FileRef ref = engine.put(data);
            assertArrayEquals(data, engine.getAll(ref), chunks + " chunks");
        }
    }

    @Test
    void wrongKeyReadsNothing() {
        DataEngine engine = new DataEngine(cluster(2, 8), smallTiered());
        FileRef ref = engine.put(sample(10_000, 8));
        FileRef wrong = new FileRef(ref.rootId(), ref.rootLocs(), new byte[32], ref.manifestSalt());
        assertThrows(FsException.class, () -> engine.getAll(wrong));
    }

    @Test
    void notEnoughNodesInTierFailsCleanlyAndLeavesNothing() {
        DataCluster c = cluster(2, 3); // tiered needs 4+4 cold nodes
        DataEngine engine = new DataEngine(c, smallTiered());
        FsException e = assertThrows(FsException.class, () -> engine.put(sample(30_000, 10)));
        assertEquals(FsErrorCode.QUORUM_FAILED, e.code());
        for (var s : concat(hotStores, coldStores)) assertEquals(Set.of(), s.listIds());
    }

    @Test
    void writeSpillsOverToHealthyNodesWhenOneRefuses() {
        DataCluster c = cluster(3, 9);
        coldStores.get(0).failWrites = true;
        DataEngine engine = new DataEngine(c, smallTiered());
        byte[] data = sample(30_000, 11);
        FileRef ref = engine.put(data);
        assertArrayEquals(data, engine.getAll(ref));
        assertTrue(coldStores.get(0).listIds().isEmpty());
    }

    @Test
    void paddingHidesExactSizeAndNoneFallbackWorksOnRandomData() {
        DataEngine engine = new DataEngine(cluster(2, 8), smallTiered().withChunkSize(4096));
        byte[] random = new byte[4000];
        new Random(1).nextBytes(random);
        FileRef ref = engine.put(random);
        assertArrayEquals(random, engine.getAll(ref));

        assertEquals(256, DataEngine.bucket(1));
        assertEquals(256, DataEngine.bucket(256));
        assertEquals(288, DataEngine.bucket(257));
        assertEquals(512, DataEngine.bucket(512));
        assertEquals(576, DataEngine.bucket(513));
        for (int len = 1; len < 100_000; len += 37) {
            int b = DataEngine.bucket(len);
            assertTrue(b >= len && b <= len * 1.126 + 256, len + "→" + b);
        }
        byte[] framed = DataEngine.frame(new byte[300], true);
        assertEquals(DataEngine.bucket(304), framed.length);
        assertEquals(300, DataEngine.unframe(framed).length);
    }

    @Test
    void streamingToOutputStream() {
        DataEngine engine = new DataEngine(cluster(2, 8), smallTiered());
        byte[] data = sample(25_000, 12);
        FileRef ref = engine.put(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        engine.get(ref, out);
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    void coldTierCostsNoMoreThanTwoHotCopies() {
        // 4+4 erasure coding stores 2x the (aggressively compressed) size, same as the 2 hot copies, but survives
        // 4 losses instead of 1; the manifest copies on both tiers add a small constant on top.
        DataEngine engine = new DataEngine(cluster(2, 8), smallTiered().withChunkSize(64 * 1024).withMetadataReplicas(1));
        byte[] data = new byte[256 * 1024];
        Random r = new Random(1);
        for (int i = 0; i < data.length; i++) data[i] = (byte) ("abcdefgh".charAt(r.nextInt(8)));
        engine.put(data);
        long hotBytes = hotStores.stream().flatMap(s -> s.listIds().stream().map(id -> s.get(id).length)).mapToLong(Integer::longValue).sum();
        long coldBytes = coldStores.stream().flatMap(s -> s.listIds().stream().map(id -> s.get(id).length)).mapToLong(Integer::longValue).sum();
        assertTrue(coldBytes <= hotBytes * 1.05, "cold=" + coldBytes + " hot=" + hotBytes);
    }

    // ── temperature belongs to the data ─────────────────────────────────────────

    @Test
    void mixedNodesAllContributeAndSurviveAnyTwoLosses() {
        DataEngine engine = new DataEngine(mixed(10, null, null), smallTiered());
        byte[] data = sample(200_000, 20);
        FileRef ref = engine.put(data);
        assertTrue(allStores.stream().allMatch(s -> !s.listIds().isEmpty()), "every node should hold something");
        for (int a = 0; a < 10; a++) {
            for (int b = a + 1; b < 10; b++) {
                mixedNodes.get(a).setAvailable(false);
                mixedNodes.get(b).setAvailable(false);
                assertArrayEquals(data, engine.getAll(ref), "nodes " + a + "," + b + " down");
                mixedNodes.get(a).setAvailable(true);
                mixedNodes.get(b).setAvailable(true);
            }
        }
    }

    @Test
    void hotDataFollowsSpeedAndColdDataFollowsCapacity() {
        double[] capacity = {1, 1, 1, 1, 12};
        double[] speed = {12, 1, 1, 1, 1};
        DataEngine engine = new DataEngine(mixed(5, capacity, speed), StoragePolicy.single().withChunkSize(1024));
        byte[] data = sample(1024 * 200, 21);
        engine.put(data); // hot: one replica per chunk, preferring node m0
        int onFast = allStores.get(0).listIds().size();
        assertTrue(onFast > totalBlobs() * 0.5, "fast node holds " + onFast + " of " + totalBlobs());

        allStores.forEach(s -> s.listIds().forEach(s::delete));
        StoragePolicy cold = StoragePolicy.archive(2, 1).withChunkSize(1024).withEcMinCipherLen(1);
        engine.put(data, cold);
        int onBig = allStores.get(4).listIds().size();
        assertTrue(onBig > allStores.get(0).listIds().size() && onBig > totalBlobs() / 5.0 * 1.5,
                "big node holds " + onBig + " of " + totalBlobs());
    }

    @Test
    void policyIsChosenPerFile() {
        DataEngine engine = new DataEngine(mixed(10, null, null), StoragePolicy.single().withChunkSize(4096));
        byte[] data = sample(40_000, 22);
        FileRef hotOnly = engine.put(data);
        int afterHot = totalBlobs();
        FileRef archived = engine.put(data, StoragePolicy.archive(4, 4).withChunkSize(4096).withEcMinCipherLen(512));
        assertTrue(totalBlobs() - afterHot > afterHot, "the archived copy spreads over many more blobs");
        assertArrayEquals(data, engine.getAll(hotOnly));
        assertArrayEquals(data, engine.getAll(archived));
    }

    @Test
    void retierMovesAFileFromHotToColdAndBack() {
        DataEngine engine = new DataEngine(mixed(10, null, null), StoragePolicy.single().withChunkSize(2048));
        byte[] data = sample(2048 * 12 + 10, 23);
        FileRef hot = engine.put(data);

        StoragePolicy archive = StoragePolicy.archive(4, 4).withChunkSize(2048).withEcMinCipherLen(256);
        FileRef cold = engine.retier(hot, archive);
        assertArrayEquals(data, engine.getAll(cold));
        // cold only: any 4 nodes may be lost
        for (int i = 0; i < 4; i++) mixedNodes.get(i).setAvailable(false);
        assertArrayEquals(data, engine.getAll(cold));
        for (int i = 0; i < 4; i++) mixedNodes.get(i).setAvailable(true);

        FileRef warm = engine.retier(cold, StoragePolicy.tiered().withChunkSize(2048).withEcMinCipherLen(256));
        assertArrayEquals(data, engine.getAll(warm));
        FileRef coldAgain = engine.retier(warm, archive);
        assertArrayEquals(data, engine.getAll(coldAgain));
        assertEquals(data.length, engine.size(coldAgain));
    }

    @Test
    void retierSegmentsAndIdempotence() {
        DataEngine engine = new DataEngine(mixed(10, null, null), StoragePolicy.single().withChunkSize(1024));
        byte[] data = sample(1024 * 10, 24);
        FileRef ref = engine.put(data);

        StoragePolicy tiered = StoragePolicy.tiered().withChunkSize(1024).withEcMinCipherLen(256);
        FileRef segmented = engine.retier(ref, StoragePolicy.single(), List.of(new DataEngine.Segment(3, 6, tiered)));
        assertArrayEquals(data, engine.getAll(segmented));
        assertArrayEquals(Arrays.copyOfRange(data, 3000, 7000), engine.read(segmented, 3000, 4000));

        int before = totalBlobs();
        FileRef again = engine.retier(segmented, StoragePolicy.single(), List.of(new DataEngine.Segment(3, 6, tiered)));
        assertEquals(before + 10, totalBlobs(), "only the new root page (5 copies per class, 2 classes) may be written");
        assertArrayEquals(data, engine.getAll(again));
    }

    @Test
    void retierFailureLeavesTheOriginalIntact() {
        DataEngine engine = new DataEngine(mixed(5, null, null), StoragePolicy.single().withChunkSize(1024));
        byte[] data = sample(1024 * 6, 25);
        FileRef ref = engine.put(data);
        int before = totalBlobs();
        // 4+4 needs 8 nodes, only 5 exist
        assertThrows(FsException.class, () -> engine.retier(ref, StoragePolicy.archive(4, 4).withChunkSize(1024).withEcMinCipherLen(1)));
        assertEquals(before, totalBlobs());
        assertArrayEquals(data, engine.getAll(ref));
    }

    @Test
    void windowSizeDoesNotChangeResults() {
        DataCluster c = mixed(10, null, null);
        byte[] data = sample(1024 * 40 + 7, 26);
        for (int window : new int[]{1, 3, 64}) {
            try (DataEngine engine = new DataEngine(c, smallTiered().withChunkSize(1024), 4, window)) {
                assertArrayEquals(data, engine.getAll(engine.put(data)), "window " + window);
            }
        }
    }
}
