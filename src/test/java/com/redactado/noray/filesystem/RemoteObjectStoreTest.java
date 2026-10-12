package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ContentDigest;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.PutResult;
import com.redactado.noray.filesystem.store.remote.BlobProtocol;
import com.redactado.noray.filesystem.store.remote.BlobResponder;
import com.redactado.noray.filesystem.store.remote.RemoteObjectStore;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import com.redactado.noray.net.InMemoryMeshFabric;
import com.redactado.noray.net.InMemoryMeshTransport;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RemoteObjectStoreTest {

    private ExecutorService executor;
    private NodeId client;
    private NodeId server;
    private InMemoryMeshTransport clientTransport;
    private InMemoryMeshTransport serverTransport;
    private InMemoryObjectStore serverStore;
    private BlobResponder responder;

    @BeforeEach
    void setUp() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        InMemoryMeshFabric fabric = new InMemoryMeshFabric();
        client = nodeId("client");
        server = nodeId("server");
        clientTransport = new InMemoryMeshTransport(client, new ConcurrentEventBus(executor, (t, c) -> {}), fabric, true);
        serverTransport = new InMemoryMeshTransport(server, new ConcurrentEventBus(executor, (t, c) -> {}), fabric, true);
        PeerCapabilities caps = new PeerCapabilities(true, false, Set.of());
        clientTransport.enroll(server, caps);
        serverTransport.enroll(client, caps);
        serverStore = new InMemoryObjectStore();
        responder = new BlobResponder(serverTransport, serverStore);
        responder.start();
    }

    @AfterEach
    void tearDown() {
        responder.close();
        executor.shutdownNow();
    }

    private static NodeId nodeId(String name) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
        return new NodeId(java.util.HexFormat.of().formatHex(hash));
    }

    @Test
    void putGetHasRoundTrip() {
        try (RemoteObjectStore remote = new RemoteObjectStore(clientTransport, server)) {
            byte[] data = "hello remote".getBytes(StandardCharsets.UTF_8);
            PutResult pr = remote.put(data);
            assertEquals(ContentDigest.sha256(data), pr.id());
            assertTrue(serverStore.has(pr.id()));
            assertTrue(remote.has(pr.id()));
            assertArrayEquals(data, remote.get(pr.id()));
            assertFalse(remote.has(BlobProtocol.randomObjectId()));
            assertThrows(FsException.class, () -> remote.get(BlobProtocol.randomObjectId()));
        }
    }

    @Test
    void repeatedCallsDoNotAccumulateSubscriptions() {
        AtomicInteger frames = new AtomicInteger();
        // Count how many handlers see a frame destined for the client.
        clientTransport.subscribe(MeshChannel.FS_BLOB, (from, p) -> frames.incrementAndGet());
        try (RemoteObjectStore remote = new RemoteObjectStore(clientTransport, server)) {
            byte[] data = {1, 2, 3};
            ObjectId id = remote.put(data).id();
            int before = frames.get();
            for (int i = 0; i < 20; i++) {
                remote.has(id);
            }
            // one reply frame per call, regardless of how many calls were made earlier
            assertEquals(before + 20, frames.get());
        }
    }

    @Test
    void closeUnsubscribesAndRejectsFurtherCalls() {
        RemoteObjectStore remote = new RemoteObjectStore(clientTransport, server);
        remote.put(new byte[] {9});
        remote.close();
        assertThrows(FsException.class, () -> remote.put(new byte[] {9}));
    }

    @Test
    void repliesEchoRequestId() {
        byte[] req = BlobProtocol.encodeHas(4242, BlobProtocol.randomObjectId());
        java.util.concurrent.atomic.AtomicReference<byte[]> reply = new java.util.concurrent.atomic.AtomicReference<>();
        clientTransport.subscribe(MeshChannel.FS_BLOB, (from, p) -> {
            if (BlobProtocol.decodeOp(p) == BlobProtocol.HAS_REPLY) reply.set(p);
        });
        clientTransport.send(server, MeshChannel.FS_BLOB, req);
        assertNotNull(reply.get());
        assertEquals(4242, BlobProtocol.decodeRequestId(reply.get()));
    }

    @Test
    void responderCloseStopsServing() {
        responder.close();
        try (RemoteObjectStore remote = new RemoteObjectStore(clientTransport, server)) {
            assertThrows(FsException.class, () -> remote.put(new byte[] {1}));
        }
    }
}
