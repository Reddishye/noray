package com.redactado.noray.net;

import com.redactado.noray.net.overlay.TrafficShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** What a network observer can see of the packets: their sizes and when they are sent. */
class TrafficShapeTest {

    @TempDir Path tmp;

    // ── the framing itself ─────────────────────────────────────────────────────

    @Test
    void paddingRoundsUpToTheBucketAndRoundTrips() {
        TrafficShape shape = new TrafficShape(256);
        for (int n : new int[]{0, 1, 40, 90, 252, 253, 255, 256, 700, 1100}) {
            byte[] payload = new byte[n];
            Arrays.fill(payload, (byte) 7);
            byte[] inner = shape.pad(3, payload);
            assertEquals(0, inner.length % 256, "length for " + n);
            byte[] back = TrafficShape.unpad(inner);
            assertEquals(3, back[0]);
            assertArrayEquals(payload, Arrays.copyOfRange(back, 1, back.length));
        }
        assertEquals(inner(shape, 1).length, inner(shape, 200).length, "small messages look alike");
        assertNotEquals(inner(shape, 1).length, inner(shape, 600).length);
    }

    private static byte[] inner(TrafficShape s, int n) {
        return s.pad(1, new byte[n]);
    }

    @Test
    void noBucketMeansNoPaddingButTheSameFormat() {
        TrafficShape none = new TrafficShape(0);
        byte[] inner = none.pad(2, new byte[10]);
        assertEquals(1 + 10 + 2, inner.length);
        assertEquals(11, TrafficShape.unpad(inner).length);
    }

    @Test
    void hugeMessagesAreNotInflatedAndMalformedPaddingIsRefused() {
        TrafficShape shape = new TrafficShape(256);
        assertEquals(1 + 60_000 + 2, shape.pad(1, new byte[60_000]).length);
        assertEquals(1 + 1300 + 2, shape.pad(1, new byte[1300]).length, "past the cap: left as it is");

        byte[] ok = shape.pad(1, new byte[5]);
        byte[] badLen = ok.clone();
        badLen[badLen.length - 1] = (byte) 0xFF;
        badLen[badLen.length - 2] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> TrafficShape.unpad(badLen));
        byte[] dirty = ok.clone();
        dirty[dirty.length - 3] = 1;            // padding must be zeros
        assertThrows(IllegalArgumentException.class, () -> TrafficShape.unpad(dirty));
        assertThrows(IllegalArgumentException.class, () -> TrafficShape.unpad(new byte[2]));
        assertTrue(TrafficShape.isCover(shape.pad(TrafficShape.COVER_CHANNEL, new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> new TrafficShape(5000));
    }

    // ── on the wire ────────────────────────────────────────────────────────────

    private OverlayRig rig;

    @BeforeEach
    void wire() throws Exception {
        rig = new OverlayRig(tmp);
    }

    @AfterEach
    void stop() {
        rig.close();
    }

    @Test
    void withPaddingAllSmallPacketsHaveTheSameSizeAndStillArriveIntact() throws Exception {
        rig.a.shapeTraffic(256, 0);
        byte[][] payloads = {new byte[]{1}, new byte[40], "have you this blob? ........".getBytes(), new byte[200]};
        Arrays.fill(payloads[1], (byte) 9);
        for (byte[] p : payloads) rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, p);
        rig.awaitB(payloads.length);

        assertEquals(payloads.length, rig.seen.size());
        assertEquals(1, new HashSet<>(rig.seen).size(), "an observer sees one size: " + rig.seen);
        for (byte[] p : payloads) {
            assertTrue(rig.receivedByB.stream().anyMatch(g -> g[0] == MeshChannel.FS_BLOB.wireId()
                    && Arrays.equals(Arrays.copyOfRange(g, 1, g.length), p)), "payload of " + p.length + " arrived intact");
        }
    }

    @Test
    void withoutPaddingSizesGiveTheMessageAway() throws Exception {
        rig.a.shapeTraffic(0, 0);
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[1]);
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[200]);
        rig.awaitB(2);
        assertEquals(2, new HashSet<>(rig.seen).size());
    }

    @Test
    void coverTrafficIsSentAtRandomAndNeverReachesTheApplication() throws Exception {
        rig.a.shapeTraffic(256, 15);
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[3]);   // establishes the session
        rig.awaitB(1);
        int afterReal = rig.seen.size();
        Thread.sleep(600);
        assertTrue(rig.seen.size() > afterReal + 5, "dummy packets were sent: " + (rig.seen.size() - afterReal));
        assertEquals(1, rig.receivedByB.size(), "the application only saw the real message");
        assertTrue(rig.seen.stream().allMatch(n -> (n - 14 - 16) % 256 == 0), "cover looks like traffic: " + new TreeSet<>(rig.seen));
    }
}
