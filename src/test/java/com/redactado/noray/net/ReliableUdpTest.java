package com.redactado.noray.net;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.redactado.noray.net.overlay.UdpOverlay;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Messages bigger than a datagram cross a network that loses and repeats packets. */
class ReliableUdpTest {

    @TempDir Path tmp;
    private OverlayRig rig;

    @BeforeEach
    void setUp() throws Exception {
        rig = new OverlayRig(tmp);
        rig.a.limits(16 * 1024 * 1024, 20_000);
        rig.b.limits(16 * 1024 * 1024, 20_000);
        rig.a.shapeTraffic(256, 0);
        rig.b.shapeTraffic(256, 0);
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static void assertDelivered(byte[] expected, byte[] got, int channel) {
        assertEquals(channel, got[0]);
        assertArrayEquals(expected, Arrays.copyOfRange(got, 1, got.length));
    }

    @Test
    void aPerPeerBandwidthCapSlowsATransferDownWithoutBreakingIt() throws Exception {
        rig.a.peerBandwidth(512 * 1024);
        byte[] chunk = random(1024 * 1024, 11);
        long t0 = System.nanoTime();
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, chunk);
        rig.awaitB(1);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertDelivered(chunk, rig.receivedByB.get(0), MeshChannel.FS_BLOB.wireId());
        assertTrue(ms >= 1200, "1 MiB at 512 KiB/s (after a small burst) takes about two seconds, took " + ms + " ms");
        assertTrue(ms < 12_000, "but it still completes: " + ms + " ms");
    }

    @Test
    void aFourMegabyteChunkArrivesIntact() throws Exception {
        byte[] chunk = random(4 * 1024 * 1024, 1);
        long t0 = System.nanoTime();
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, chunk);
        rig.awaitB(1);
        assertEquals(1, rig.receivedByB.size());
        assertDelivered(chunk, rig.receivedByB.get(0), MeshChannel.FS_BLOB.wireId());
        System.out.println("4 MiB over loopback in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        assertTrue(rig.seen.stream().allMatch(n -> n < 1500), "no datagram needs IP fragmentation");
    }

    @Test
    void messagesJustAroundTheFragmentBoundaryAllArrive() throws Exception {
        int[] sizes = {999, 1000, 1001, 2000, 2001, 5000};
        for (int n : sizes) rig.a.sendPlain(rig.idB, MeshChannel.FS_REF, random(n, n));
        rig.awaitB(sizes.length);
        assertEquals(sizes.length, rig.receivedByB.size());
        for (int n : sizes) {
            assertTrue(rig.receivedByB.stream().anyMatch(g -> g.length == n + 1 && Arrays.equals(Arrays.copyOfRange(g, 1, g.length), random(n, n))),
                    "size " + n);
        }
    }

    @Test
    void lossOfAThirdOfAllPacketsStillDeliversEverything() throws Exception {
        Random loss = new Random(42);
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{1}); // handshake and session first
        rig.awaitB(1);
        rig.drop = d -> loss.nextInt(3) == 0;
        // the property is "everything arrives", not speed: under full-suite CPU load 20 s was too tight
        rig.a.limits(16 * 1024 * 1024, 60_000);
        byte[] data = random(600_000, 7);
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, data);
        rig.awaitB(2);
        assertEquals(2, rig.receivedByB.size());
        assertDelivered(data, rig.receivedByB.get(1), MeshChannel.FS_BLOB.wireId());
    }

    @Test
    void duplicatedPacketsDoNotDeliverTwiceOrBreakAnything() throws Exception {
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{1});
        rig.awaitB(1);
        rig.duplicate = d -> true;   // every packet arrives twice (the replay window drops the copies)
        byte[] data = random(50_000, 3);
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, data);
        rig.awaitB(2);
        Thread.sleep(300);
        assertEquals(2, rig.receivedByB.size());
        assertDelivered(data, rig.receivedByB.get(1), MeshChannel.FS_BLOB.wireId());
    }

    @Test
    void lostAcknowledgementsMakeTheSenderRetransmitWithoutRedelivery() throws Exception {
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{1});
        rig.awaitB(1);
        Random r = new Random(5);
        // packets from B to A (acks) are small; drop most of them for a while
        rig.drop = d -> d.length <= 14 + 16 + 256 && r.nextInt(10) < 8;
        byte[] data = random(40_000, 9);
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, data);
        rig.awaitB(2);
        Thread.sleep(300);
        assertEquals(2, rig.receivedByB.size(), "delivered exactly once");
        assertDelivered(data, rig.receivedByB.get(1), MeshChannel.FS_BLOB.wireId());
    }

    @Test
    void bothDirectionsAtOnceAndInterleavedMessages() throws Exception {
        byte[] x = random(300_000, 11), y = random(200_000, 12), z = random(250_000, 13);
        Thread t1 = Thread.startVirtualThread(() -> rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, x));
        Thread t2 = Thread.startVirtualThread(() -> rig.a.sendPlain(rig.idB, MeshChannel.FS_REPAIR, y));
        // let the session exist before B talks back
        Thread.sleep(50);
        Thread t3 = Thread.startVirtualThread(() -> rig.b.sendPlain(rig.idA, MeshChannel.FS_REF, z));
        t1.join(20_000);
        t2.join(20_000);
        t3.join(20_000);
        rig.awaitB(2);
        rig.awaitA(1);
        assertEquals(2, rig.receivedByB.size());
        assertEquals(1, rig.receivedByA.size());
        assertTrue(rig.receivedByB.stream().anyMatch(g -> g[0] == MeshChannel.FS_BLOB.wireId() && Arrays.equals(Arrays.copyOfRange(g, 1, g.length), x)));
        assertTrue(rig.receivedByB.stream().anyMatch(g -> g[0] == MeshChannel.FS_REPAIR.wireId() && Arrays.equals(Arrays.copyOfRange(g, 1, g.length), y)));
        assertDelivered(z, rig.receivedByA.get(0), MeshChannel.FS_REF.wireId());
    }

    @Test
    void aHandlerMayAnswerWithALargeMessageWithoutDeadlock() throws Exception {
        byte[] reply = random(300_000, 21);
        rig.b.setReceiver((from, plain) -> rig.b.sendPlain(rig.idA, MeshChannel.FS_BLOB, reply));
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, random(100_000, 20));
        rig.awaitA(1);
        assertEquals(1, rig.receivedByA.size());
        assertDelivered(reply, rig.receivedByA.get(0), MeshChannel.FS_BLOB.wireId());
    }

    @Test
    void anUnreachablePeerFailsTheSendInsteadOfHangingForever() throws Exception {
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{1});
        rig.awaitB(1);
        rig.a.limits(16 * 1024 * 1024, 800);
        rig.drop = d -> true; // the network is gone
        long t0 = System.nanoTime();
        FsException e = assertThrows(FsException.class,
                () -> rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, random(100_000, 1)));
        assertEquals(FsErrorCode.VOLUME_UNAVAILABLE, e.code());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000);
    }

    @Test
    void messagesOverTheLimitAreRefusedAtBothEnds() throws Exception {
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{1});
        rig.awaitB(1);
        rig.a.limits(50_000, 2_000);
        assertThrows(FsException.class, () -> rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[60_000]));

        // the receiver does not let a larger announced message use its memory either
        rig.b.limits(50_000, 2_000);
        rig.a.limits(16 * 1024 * 1024, 1_000);
        assertThrows(FsException.class, () -> rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[200_000]));
        assertEquals(1, rig.receivedByB.size());
    }

    @Test
    void noPacketOnTheWireNamesANodeAndIndicesDifferPerSession() throws Exception {
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{1});
        rig.b.sendPlain(rig.idA, MeshChannel.FS_BLOB, new byte[]{2});
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, random(30_000, 4));
        rig.awaitB(2);
        rig.awaitA(1);
        assertFalse(rig.captured.isEmpty());
        for (byte[] d : rig.captured) {
            String hex = java.util.HexFormat.of().formatHex(d);
            assertFalse(hex.contains(rig.idA.hex()), "sender id in a datagram");
            assertFalse(hex.contains(rig.idB.hex()), "receiver id in a datagram");
        }
        // two sessions with the same peer use other indices
        java.util.Set<Integer> first = new java.util.HashSet<>();
        for (byte[] d : rig.captured) first.add(java.nio.ByteBuffer.wrap(d, 2, 4).getInt());
        rig.captured.clear();
        rig.a.close();
        try (var again = new OverlayRig(java.nio.file.Files.createTempDirectory("rig"))) {
            again.a.sendPlain(again.idB, MeshChannel.FS_BLOB, new byte[]{1});
            again.awaitB(1);
            for (byte[] d : again.captured) {
                assertFalse(first.contains(java.nio.ByteBuffer.wrap(d, 2, 4).getInt()), "indices are random per session");
            }
        }
    }

    @Test
    void obfuscatedDatagramsShowNoProtocolHeaderAndStillWork() throws Exception {
        rig.a.obfuscate(true);
        rig.b.obfuscate(true);
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{1});
        byte[] data = random(60_000, 21);
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, data);
        rig.awaitB(2);
        assertDelivered(data, rig.receivedByB.get(1), MeshChannel.FS_BLOB.wireId());
        long looksLikeUs = rig.captured.stream()
                .filter(d -> d[0] == UdpOverlay.VERSION && d[1] >= 1 && d[1] <= 4).count();
        assertTrue(rig.captured.size() > 50);
        assertTrue(looksLikeUs <= 1, "version and type bytes must not stand out: " + looksLikeUs);
    }
}
