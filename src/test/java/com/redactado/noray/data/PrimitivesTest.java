package com.redactado.noray.data;

import com.redactado.noray.data.codec.Codecs;
import com.redactado.noray.data.crypto.DataCrypto;
import com.redactado.noray.data.manifest.FileRef;
import com.redactado.noray.data.manifest.Manifest.BlobLoc;
import com.redactado.noray.data.manifest.Manifest.Chunk;
import com.redactado.noray.data.manifest.Manifest.Page;
import com.redactado.noray.data.manifest.Manifest.Variant;
import com.redactado.noray.data.manifest.ManifestCodec;
import com.redactado.noray.data.placement.Placement;
import com.redactado.noray.data.placement.SimpleNode;
import com.redactado.noray.data.placement.StorageNode;
import com.redactado.noray.data.placement.Tier;
import com.redactado.noray.data.redundancy.ReedSolomon;
import com.redactado.noray.data.redundancy.Redundancy;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ContentDigest;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PrimitivesTest {

    @Test
    void reedSolomonRebuildsFromAnyKShards() {
        int k = 4, m = 3, len = 37;
        Random rnd = new Random(1);
        byte[][] data = new byte[k][len];
        for (byte[] d : data) rnd.nextBytes(d);
        byte[][] parity = new ReedSolomon(k, m).encode(data);
        byte[][] all = new byte[k + m][];
        for (int i = 0; i < k; i++) all[i] = data[i];
        for (int i = 0; i < m; i++) all[k + i] = parity[i];

        // every subset of exactly k shards (7 choose 4 = 35)
        for (int mask = 0; mask < (1 << (k + m)); mask++) {
            if (Integer.bitCount(mask) != k) continue;
            byte[][] partial = new byte[k + m][];
            for (int i = 0; i < k + m; i++) if ((mask & (1 << i)) != 0) partial[i] = all[i].clone();
            byte[][] rebuilt = new ReedSolomon(k, m).decode(partial, len);
            for (int i = 0; i < k; i++) assertArrayEquals(data[i], rebuilt[i], "mask " + mask + " shard " + i);
        }
    }

    @Test
    void reedSolomonFailsWithTooFewShards() {
        ReedSolomon rs = new ReedSolomon(3, 2);
        byte[][] shards = new byte[5][];
        shards[0] = new byte[4];
        shards[4] = new byte[4];
        assertThrows(RuntimeException.class, () -> rs.decode(shards, 4));
    }

    @Test
    void codecsRoundTripAndResistBombs() {
        byte[] plain = new byte[100_000]; // very compressible
        for (int id : new int[]{Codecs.NONE_ID, Codecs.DEFLATE_FAST_ID, Codecs.DEFLATE_MAX_ID}) {
            byte[] c = Codecs.byId(id).compress(plain);
            assertArrayEquals(plain, Codecs.byId(id).decompress(c, plain.length));
        }
        byte[] bomb = Codecs.byId(Codecs.DEFLATE_MAX_ID).compress(new byte[10_000_000]);
        FsException e = assertThrows(FsException.class, () -> Codecs.byId(Codecs.DEFLATE_MAX_ID).decompress(bomb, 1000));
        assertEquals(FsErrorCode.DIGEST_MISMATCH, e.code());
        assertTrue(Codecs.byId(Codecs.DEFLATE_MAX_ID).compress(plain).length
                <= Codecs.byId(Codecs.DEFLATE_FAST_ID).compress(plain).length);
    }

    @Test
    void cryptoDetectsTamperingAndBindsAad() {
        byte[] key = DataCrypto.randomKey();
        byte[] aad = {1, 2, 3};
        byte[] sealed = DataCrypto.seal(key, aad, "secret".getBytes());
        assertArrayEquals("secret".getBytes(), DataCrypto.open(key, aad, sealed));

        byte[] flipped = sealed.clone();
        flipped[0] ^= 1;
        assertEquals(FsErrorCode.DIGEST_MISMATCH,
                assertThrows(FsException.class, () -> DataCrypto.open(key, aad, flipped)).code());
        assertThrows(FsException.class, () -> DataCrypto.open(key, new byte[]{9}, sealed));
        assertThrows(FsException.class, () -> DataCrypto.open(DataCrypto.randomKey(), aad, sealed));

        assertFalse(java.util.Arrays.equals(DataCrypto.derive(key, "a"), DataCrypto.derive(key, "b")));
        assertArrayEquals(DataCrypto.derive(key, "a"), DataCrypto.derive(key, "a"));
    }

    private static List<StorageNode> nodes(int n, int domains, double weight) {
        List<StorageNode> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new SimpleNode("n" + i, "d" + (i % domains), weight, 1, new InMemoryObjectStore()));
        }
        return out;
    }

    @Test
    void placementIsDeterministicAndSpreadsDomains() {
        List<StorageNode> nodes = nodes(12, 4, 1);
        List<StorageNode> a = Placement.candidates(nodes, "key");
        List<StorageNode> b = Placement.candidates(new ArrayList<>(nodes.reversed()), "key");
        assertEquals(a, b);
        assertEquals(4, a.subList(0, 4).stream().map(StorageNode::domain).distinct().count());
    }

    @Test
    void placementWeightFunctionSelectsSpeedOrCapacity() {
        List<StorageNode> nodes = new ArrayList<>();
        nodes.add(new SimpleNode("fast", "a", 1, 9, new InMemoryObjectStore()));
        nodes.add(new SimpleNode("big", "b", 9, 1, new InMemoryObjectStore()));
        int fastFirstHot = 0, bigFirstCold = 0;
        for (int i = 0; i < 2000; i++) {
            if (Placement.candidates(nodes, "k" + i, n -> n.weightFor(Tier.HOT)).get(0).id().equals("fast")) fastFirstHot++;
            if (Placement.candidates(nodes, "k" + i, n -> n.weightFor(Tier.COLD)).get(0).id().equals("big")) bigFirstCold++;
        }
        assertTrue(fastFirstHot > 1650 && bigFirstCold > 1650, fastFirstHot + "/" + bigFirstCold);
    }

    @Test
    void placementRespectsWeightsAndMovesLittleOnChange() {
        List<StorageNode> nodes = new ArrayList<>();
        nodes.add(new SimpleNode("big", "x", 4, 1, new InMemoryObjectStore()));
        for (int i = 0; i < 4; i++) nodes.add(new SimpleNode("s" + i, "d" + i, 1, 1, new InMemoryObjectStore()));
        Map<String, Integer> firsts = new HashMap<>();
        for (int i = 0; i < 4000; i++) {
            firsts.merge(Placement.candidates(nodes, "k" + i).get(0).id(), 1, Integer::sum);
        }
        // big has weight 4 of 8 → about half
        assertTrue(firsts.get("big") > 1700 && firsts.get("big") < 2300, firsts.toString());

        List<StorageNode> fewer = new ArrayList<>(nodes);
        fewer.remove(1);
        int moved = 0;
        for (int i = 0; i < 2000; i++) {
            String before = Placement.candidates(nodes, "k" + i).get(0).id();
            String after = Placement.candidates(fewer, "k" + i).get(0).id();
            if (!before.equals(after)) {
                assertEquals("s0", before, "only data owned by the removed node may move");
                moved++;
            }
        }
        assertTrue(moved > 0);
    }

    @Test
    void manifestAndFileRefRoundTrip() {
        BlobLoc loc = new BlobLoc("n1", ContentDigest.sha256(new byte[]{1}), 0);
        Variant v1 = new Variant(Tier.HOT, 1, Redundancy.replicas(2), 300, 0, 77L, List.of(loc, loc));
        Variant v2 = new Variant(Tier.COLD, 2, Redundancy.erasure(2, 1), 300, 150, -5L, List.of(loc, loc, loc));
        Page leaf = new Page(0, 4096, 8, 5000, List.of(new Chunk(4096, List.of(v1, v2))), List.of());
        Page back = ManifestCodec.decode(ManifestCodec.encode(leaf));
        assertEquals(leaf, back);
        assertThrows(RuntimeException.class, () -> ManifestCodec.decode(new byte[]{1, 2, 3}));

        FileRef ref = new FileRef(loc.blobId(), List.of(loc), DataCrypto.randomKey(), 0xfeedL);
        FileRef parsed = FileRef.parse(ref.encode());
        assertEquals(ref.rootId(), parsed.rootId());
        assertEquals(ref.rootLocs(), parsed.rootLocs());
        assertArrayEquals(ref.key(), parsed.key());
        assertEquals(0xfeedL, parsed.manifestSalt());
        assertEquals(ref, parsed);
        FileRef negative = new FileRef(loc.blobId(), List.of(), DataCrypto.randomKey(), -1L);
        assertEquals(-1L, FileRef.parse(negative.encode()).manifestSalt());
        assertFalse(ref.toString().contains(java.util.HexFormat.of().formatHex(ref.key())));
    }

    @Test
    void storedBytesEstimateFollowsRedundancy() {
        assertEquals(4_000, com.redactado.noray.data.StoragePolicy.tiered().storedBytesEstimate(1_000), "2 replicas + RS 4+4");
        assertEquals(1_500, com.redactado.noray.data.StoragePolicy.archive(4, 2).storedBytesEstimate(1_000));
        assertEquals(1_000, com.redactado.noray.data.StoragePolicy.single().storedBytesEstimate(1_000));
        assertEquals(0, com.redactado.noray.data.StoragePolicy.tiered().storedBytesEstimate(-5));
    }
}
