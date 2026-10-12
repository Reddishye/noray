package com.redactado.noray.net;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class MeshTransportTest {
    private ExecutorService executor;
    private ConcurrentEventBus eventBusA;
    private ConcurrentEventBus eventBusB;
    private InMemoryMeshFabric fabric;
    private NodeId nodeA;
    private NodeId nodeB;
    private InMemoryMeshTransport transportA;
    private InMemoryMeshTransport transportB;

    @BeforeEach
    void setUp() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        eventBusA = new ConcurrentEventBus(executor, (t, ctx) -> {});
        eventBusB = new ConcurrentEventBus(executor, (t, ctx) -> {});
        fabric = new InMemoryMeshFabric();

        nodeA = nodeId("nodeA");
        nodeB = nodeId("nodeB");

        transportA = new InMemoryMeshTransport(nodeA, eventBusA, fabric, true);
        transportB = new InMemoryMeshTransport(nodeB, eventBusB, fabric, true);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private NodeId nodeId(String name) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(name.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return new NodeId(sb.toString());
    }

    @Test
    void notEnrolledSendThrowsDenied() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);

        FsException ex = assertThrows(FsException.class,
                () -> transportA.send(nodeB, MeshChannel.FS_BLOB, payload));
        assertEquals(FsErrorCode.DENIED, ex.code());
    }

    @Test
    void enrollBothWaysThenSendDelivers() {
        PeerCapabilities caps = new PeerCapabilities(true, false, Set.of());
        transportA.enroll(nodeB, caps);
        transportB.enroll(nodeA, caps);

        List<byte[]> received = new ArrayList<>();
        transportB.subscribe(MeshChannel.FS_BLOB, (from, payload) -> received.add(payload));

        byte[] payload = "Mesh message".getBytes(StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> transportA.send(nodeB, MeshChannel.FS_BLOB, payload));

        assertEquals(1, received.size());
        assertArrayEquals(payload, received.get(0));
    }

    @Test
    void revokeThenSendThrowsDenied() {
        PeerCapabilities caps = new PeerCapabilities(true, false, Set.of());
        transportA.enroll(nodeB, caps);
        transportB.enroll(nodeA, caps);

        transportA.revoke(nodeB);

        FsException ex = assertThrows(FsException.class,
                () -> transportA.send(nodeB, MeshChannel.FS_BLOB, "test".getBytes(StandardCharsets.UTF_8)));
        assertEquals(FsErrorCode.DENIED, ex.code());
    }

    @Test
    void unknownPeerSendThrowsDenied() throws Exception {
        NodeId unknownNode = nodeId("unknownNode");

        FsException ex = assertThrows(FsException.class,
                () -> transportA.send(unknownNode, MeshChannel.CTRL, new byte[]{1}));
        assertEquals(FsErrorCode.DENIED, ex.code());
    }
}
