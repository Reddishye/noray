package com.redactado.noray.net;

import com.redactado.noray.net.enroll.EnrollmentRecord;
import com.redactado.noray.net.enroll.EnrollmentStore;
import com.redactado.noray.net.overlay.StaticKeys;
import com.redactado.noray.net.overlay.UdpOverlay;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rotating the mesh key without a flag day: a node that holds the new key and remembers the old one talks to nodes on
 * either, whichever side starts, with or without obfuscation. Two nodes with different keys and no rotation still cannot.
 */
class PskRotationTest {

    @TempDir Path tmp;

    private static EnrollmentStore store() {
        Map<NodeId, EnrollmentRecord> map = new ConcurrentHashMap<>();
        return new EnrollmentStore() {
            @Override public void save(EnrollmentRecord r) { map.put(r.nodeId(), r); }
            @Override public void delete(NodeId n) { map.remove(n); }
            @Override public Optional<EnrollmentRecord> find(NodeId n) { return Optional.ofNullable(map.get(n)); }
            @Override public List<EnrollmentRecord> list() { return List.copyOf(map.values()); }
        };
    }

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    private static void await(List<byte[]> l, int n) throws InterruptedException {
        for (int i = 0; i < 800 && l.size() < n; i++) Thread.sleep(10);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNodeWithBothKeysTalksToOldAndNewNodesWhoeverStarts(boolean obfuscate) throws Exception {
        byte[] oldKey = key(1), newKey = key(2);
        NodeId idA = OverlayRig.id(21), idB = OverlayRig.id(22), idC = OverlayRig.id(23);
        StaticKeys ka = StaticKeys.loadOrCreate(tmp.resolve("a"), idA);
        StaticKeys kb = StaticKeys.loadOrCreate(tmp.resolve("b"), idB);
        StaticKeys kc = StaticKeys.loadOrCreate(tmp.resolve("c"), idC);
        EnrollmentStore sa = store(), sb = store(), sc = store();
        UdpOverlay a = new UdpOverlay(idA, ka, sa, newKey, 0);   // rotating: new key, remembers the old
        UdpOverlay b = new UdpOverlay(idB, kb, sb, oldKey, 0);   // not yet updated
        UdpOverlay c = new UdpOverlay(idC, kc, sc, newKey, 0);   // already updated, knows nothing of the old key
        try {
            a.rotateFrom(oldKey);
            for (UdpOverlay o : List.of(a, b, c)) {
                o.obfuscate(obfuscate);
                o.shapeTraffic(256, 0);
            }
            a.limits(1 << 20, 20_000); b.limits(1 << 20, 20_000); c.limits(1 << 20, 20_000);
            String ha = "127.0.0.1:" + a.localPort(), hb = "127.0.0.1:" + b.localPort(), hc = "127.0.0.1:" + c.localPort();
            sa.save(new EnrollmentRecord(idB, true, false, Set.of(), hb, kb.x25519PubHex(), kb.mlkemPubHex()));
            sa.save(new EnrollmentRecord(idC, true, false, Set.of(), hc, kc.x25519PubHex(), kc.mlkemPubHex()));
            sb.save(new EnrollmentRecord(idA, true, false, Set.of(), ha, ka.x25519PubHex(), ka.mlkemPubHex()));
            sc.save(new EnrollmentRecord(idA, true, false, Set.of(), ha, ka.x25519PubHex(), ka.mlkemPubHex()));
            List<byte[]> atA = new CopyOnWriteArrayList<>(), atB = new CopyOnWriteArrayList<>(), atC = new CopyOnWriteArrayList<>();
            a.setReceiver((f, p) -> atA.add(p));
            b.setReceiver((f, p) -> atB.add(p));
            c.setReceiver((f, p) -> atC.add(p));

            a.sendPlain(idB, MeshChannel.FS_BLOB, new byte[]{1});   // new initiates to an old node
            await(atB, 1);
            assertEquals(1, atB.size(), "A (both keys) reaches B (old key)");
            b.sendPlain(idA, MeshChannel.FS_BLOB, new byte[]{2});
            await(atA, 1);
            assertEquals(1, atA.size());

            c.sendPlain(idA, MeshChannel.FS_BLOB, new byte[]{3});   // an updated node initiates to the rotating one
            await(atA, 2);
            assertEquals(2, atA.size(), "C (new key) reaches A");
            a.sendPlain(idC, MeshChannel.FS_BLOB, new byte[]{4});
            await(atC, 1);
            assertEquals(1, atC.size());
        } finally {
            a.close(); b.close(); c.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void withoutRotationDifferentKeysStayApart(boolean obfuscate) throws Exception {
        NodeId idB = OverlayRig.id(32), idC = OverlayRig.id(33);
        StaticKeys kb = StaticKeys.loadOrCreate(tmp.resolve("b"), idB);
        StaticKeys kc = StaticKeys.loadOrCreate(tmp.resolve("c"), idC);
        EnrollmentStore sb = store(), sc = store();
        UdpOverlay b = new UdpOverlay(idB, kb, sb, key(1), 0);
        UdpOverlay c = new UdpOverlay(idC, kc, sc, key(2), 0);
        try {
            b.obfuscate(obfuscate); c.obfuscate(obfuscate);
            b.limits(1 << 20, 2_000); c.limits(1 << 20, 2_000);
            sb.save(new EnrollmentRecord(idC, true, false, Set.of(), "127.0.0.1:" + c.localPort(), kc.x25519PubHex(), kc.mlkemPubHex()));
            sc.save(new EnrollmentRecord(idB, true, false, Set.of(), "127.0.0.1:" + b.localPort(), kb.x25519PubHex(), kb.mlkemPubHex()));
            assertThrows(RuntimeException.class, () -> b.sendPlain(idC, MeshChannel.FS_BLOB, new byte[]{1}));
        } finally {
            b.close(); c.close();
        }
    }
}
