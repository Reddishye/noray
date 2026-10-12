package com.redactado.noray.net;

import com.redactado.noray.kernel.metrics.Metrics;
import com.redactado.noray.net.overlay.UdpOverlay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** A recorded handshake is useless to an attacker, and long sessions do not live forever. */
class HandshakeHardeningTest {

    @TempDir Path tmp;
    private OverlayRig rig;

    @BeforeEach
    void setUp() throws Exception {
        rig = new OverlayRig(tmp);
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private long handshakes() {
        return rig.captured.stream().filter(d -> d.length > 1 && d[1] == UdpOverlay.TYPE_HS).count();
    }

    @Test
    void aReplayedMessage1GetsNoAnswerAndChangesNothing() throws Exception {
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{1});
        rig.awaitB(1);
        byte[] recorded = rig.captured.stream().filter(d -> d.length > 1 && d[1] == UdpOverlay.TYPE_HS).findFirst().orElseThrow();
        long answersBefore = rig.captured.stream().filter(d -> d.length > 1 && d[1] == UdpOverlay.TYPE_HS2).count();
        long replayedBefore = Metrics.global().count("net.handshakes.replayed");
        Thread.sleep(400); // beyond the minimum gap between handshakes from one peer

        try (DatagramSocket attacker = new DatagramSocket()) {
            attacker.send(new DatagramPacket(recorded, recorded.length, InetAddress.getLoopbackAddress(), rig.b.localPort()));
        }
        for (int i = 0; i < 100 && Metrics.global().count("net.handshakes.replayed") == replayedBefore; i++) Thread.sleep(20);

        assertEquals(replayedBefore + 1, Metrics.global().count("net.handshakes.replayed"));
        assertEquals(answersBefore, rig.captured.stream().filter(d -> d.length > 1 && d[1] == UdpOverlay.TYPE_HS2).count(),
                "the responder stays silent");
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{2}); // the real session is untouched
        rig.awaitB(2);
        assertEquals(2, rig.receivedByB.size());
    }

    @Test
    void sessionsAreReplacedAfterTheirLifetime() throws Exception {
        rig.a.sessionLifetime(Duration.ofSeconds(1));
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{1});
        rig.awaitB(1);
        assertEquals(1, handshakes());
        Thread.sleep(1300);
        rig.a.sendPlain(rig.idB, MeshChannel.FS_BLOB, new byte[]{2});
        rig.awaitB(2);
        assertEquals(2, rig.receivedByB.size(), "traffic continues across the replacement");
        assertEquals(2, handshakes(), "a second handshake replaced the aged session");
    }
}
