package com.redactado.noray.net;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.enroll.EnrollmentRecord;
import com.redactado.noray.net.enroll.EnrollmentStore;
import com.redactado.noray.net.overlay.StaticKeys;
import com.redactado.noray.net.overlay.UdpOverlay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A and B cannot reach each other (neither knows the other's address); both can reach R. Everything between A and B
 * goes through R, which sees only datagrams it cannot read.
 */
class RelayTest {

    @TempDir Path tmp;

    private final NodeId idA = OverlayRig.id(11), idB = OverlayRig.id(12), idR = OverlayRig.id(13);
    private UdpOverlay a, b, r;
    private final List<byte[]> receivedByA = new CopyOnWriteArrayList<>();
    private final List<byte[]> receivedByB = new CopyOnWriteArrayList<>();
    private final List<byte[]> receivedByR = new CopyOnWriteArrayList<>();
    private StaticKeys ka, kb, kr;
    private EnrollmentStore sa, sb, sr;

    private static EnrollmentStore store() {
        Map<NodeId, EnrollmentRecord> map = new ConcurrentHashMap<>();
        return new EnrollmentStore() {
            @Override public void save(EnrollmentRecord r) { map.put(r.nodeId(), r); }
            @Override public void delete(NodeId n) { map.remove(n); }
            @Override public Optional<EnrollmentRecord> find(NodeId n) { return Optional.ofNullable(map.get(n)); }
            @Override public List<EnrollmentRecord> list() { return List.copyOf(map.values()); }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        byte[] psk = new byte[32];
        Arrays.fill(psk, (byte) 7);
        ka = StaticKeys.loadOrCreate(tmp.resolve("a"), idA);
        kb = StaticKeys.loadOrCreate(tmp.resolve("b"), idB);
        kr = StaticKeys.loadOrCreate(tmp.resolve("r"), idR);
        sa = store(); sb = store(); sr = store();
        a = new UdpOverlay(idA, ka, sa, psk, 0);
        b = new UdpOverlay(idB, kb, sb, psk, 0);
        r = new UdpOverlay(idR, kr, sr, psk, 0);
        for (UdpOverlay o : List.of(a, b, r)) {
            o.obfuscate(true);
            o.shapeTraffic(256, 0);
            o.limits(16 * 1024 * 1024, 20_000);
        }
        // A and B know only R's address; A is told to reach B through R
        sa.save(new EnrollmentRecord(idR, true, false, Set.of(), "127.0.0.1:" + r.localPort(), kr.x25519PubHex(), kr.mlkemPubHex()));
        sb.save(new EnrollmentRecord(idR, true, false, Set.of(), "127.0.0.1:" + r.localPort(), kr.x25519PubHex(), kr.mlkemPubHex()));
        sa.save(new EnrollmentRecord(idB, true, false, Set.of(), "relay:" + idR.hex(), kb.x25519PubHex(), kb.mlkemPubHex()));
        sb.save(new EnrollmentRecord(idA, true, false, Set.of(), "relay:" + idR.hex(), ka.x25519PubHex(), ka.mlkemPubHex()));
        // R knows both as peers, without addresses: they come to it
        sr.save(new EnrollmentRecord(idA, true, false, Set.of(), "", ka.x25519PubHex(), ka.mlkemPubHex()));
        sr.save(new EnrollmentRecord(idB, true, false, Set.of(), "", kb.x25519PubHex(), kb.mlkemPubHex()));
        a.setReceiver((from, p) -> receivedByA.add(p));
        b.setReceiver((from, p) -> receivedByB.add(p));
        r.setReceiver((from, p) -> receivedByR.add(p));
    }

    @AfterEach
    void tearDown() {
        a.close(); b.close(); r.close();
    }

    private static byte[] random(int n, long seed) {
        byte[] x = new byte[n];
        new Random(seed).nextBytes(x);
        return x;
    }

    private static void await(List<byte[]> l, int n) throws InterruptedException {
        for (int i = 0; i < 1500 && l.size() < n; i++) Thread.sleep(10);
    }

    @Test
    void twoPeersThatCannotSeeEachOtherTalkThroughTheRelayInBothDirections() throws Exception {
        r.enableRelay(true, 8L * 1024 * 1024);
        b.keepConnected(Set.of(idR), 1); // B is reachable only because it keeps a session open to R
        b.sendPlain(idR, MeshChannel.FS_BLOB, new byte[]{0}); // wait until that session exists

        a.sendPlain(idB, MeshChannel.FS_BLOB, new byte[]{1, 2, 3});
        await(receivedByB, 1);
        assertEquals(1, receivedByB.size());

        byte[] big = random(300_000, 5);
        a.sendPlain(idB, MeshChannel.FS_BLOB, big);
        await(receivedByB, 2);
        assertArrayEquals(big, Arrays.copyOfRange(receivedByB.get(1), 1, receivedByB.get(1).length));

        byte[] back = random(120_000, 6);
        b.sendPlain(idA, MeshChannel.FS_BLOB, back);
        await(receivedByA, 1);
        assertArrayEquals(back, Arrays.copyOfRange(receivedByA.get(0), 1, receivedByA.get(0).length));
        assertEquals(1, receivedByR.size(), "the relay got only B's own message to it, none of what it carried");
    }

    @Test
    void aNodeThatDoesNotRelayDropsTheTraffic() throws Exception {
        b.keepConnected(Set.of(idR), 1);       // R accepts sessions but is not a relay
        b.sendPlain(idR, MeshChannel.FS_BLOB, new byte[]{0});
        FsException e = assertThrows(FsException.class, () -> a.sendPlain(idB, MeshChannel.FS_BLOB, new byte[]{1}));
        assertEquals(FsErrorCode.VOLUME_UNAVAILABLE, e.code());
        assertTrue(receivedByB.isEmpty());
    }

    @Test
    void theRelayDoesNotCarryTrafficOfAPeerItDoesNotKnow() throws Exception {
        r.enableRelay(true, 8L * 1024 * 1024);
        b.keepConnected(Set.of(idR), 1);
        b.sendPlain(idR, MeshChannel.FS_BLOB, new byte[]{0});
        sr.delete(idA);                          // R no longer knows A: its handshake gets no answer at all
        FsException e = assertThrows(FsException.class, () -> a.sendPlain(idB, MeshChannel.FS_BLOB, new byte[]{1}));
        assertEquals(FsErrorCode.VOLUME_UNAVAILABLE, e.code());
    }
}
