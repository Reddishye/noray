package com.redactado.noray.net;

import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.remote.BlobResponder;
import com.redactado.noray.filesystem.store.remote.RemoteObjectStore;
import com.redactado.noray.kernel.core.NorayKernel;
import com.redactado.noray.net.enroll.EnrollmentRecord;
import com.redactado.noray.net.enroll.EnrollmentStore;
import com.redactado.noray.net.overlay.StaticKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Two complete nodes (net module, real UDP sockets, real handshake) exchanging blobs the size the data engine uses. */
class RealNodesTest {

    @TempDir Path tmp;

    private static int freePort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private NorayKernel node(String name, int port) {
        NorayKernel k = new NorayKernel(Map.of("noray.data.dir", tmp.resolve(name).toString(),
                "noray.net.udp.port", String.valueOf(port),
                "noray.net.mesh.psk", "ab".repeat(32),
                "noray.net.obfuscate", "true"));
        k.registerModule(new NetModule());
        k.boot();
        return k;
    }

    @Test
    void aFourMegabyteBlobCrossesRealUdpBetweenTwoNodesAndComesBack() throws Exception {
        int portA = freePort(), portB = freePort();
        try (NorayKernel a = node("a", portA); NorayKernel b = node("b", portB)) {
            var ra = a.getContext().getServiceRegistry();
            var rb = b.getContext().getServiceRegistry();
            MeshTransport ta = ra.require(MeshTransport.class), tb = rb.require(MeshTransport.class);
            StaticKeys ka = ra.require(StaticKeys.class), kb = rb.require(StaticKeys.class);
            EnrollmentStore sa = ra.require(EnrollmentStore.class), sb = rb.require(EnrollmentStore.class);
            var caps = new PeerCapabilities(true, false, Set.of());

            sa.save(new EnrollmentRecord(tb.localId(), true, false, Set.of(), "127.0.0.1:" + portB,
                    kb.x25519PubHex(), kb.mlkemPubHex()));
            ta.enroll(tb.localId(), caps);
            sb.save(new EnrollmentRecord(ta.localId(), true, false, Set.of(), "127.0.0.1:" + portA,
                    ka.x25519PubHex(), ka.mlkemPubHex()));
            tb.enroll(ta.localId(), caps);

            InMemoryObjectStore served = new InMemoryObjectStore();
            try (BlobResponder responder = new BlobResponder(tb, served); RemoteObjectStore remote = new RemoteObjectStore(ta, tb.localId())) {
                responder.start();
                byte[] chunk = new byte[4 * 1024 * 1024];
                new Random(1).nextBytes(chunk);

                ObjectId id = remote.put(chunk).id();
                assertTrue(served.has(id), "the other node stored it");
                assertTrue(remote.has(id));
                assertEquals(chunk.length, remote.size(id));
                assertArrayEquals(chunk, remote.get(id), "and sent it back intact");
            }
        }
    }
}
