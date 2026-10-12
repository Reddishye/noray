package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.remote.BlobResponder;
import com.redactado.noray.filesystem.store.remote.RemoteObjectStore;
import com.redactado.noray.identity.AccessPolicy;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import com.redactado.noray.net.InMemoryMeshFabric;
import com.redactado.noray.net.InMemoryMeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** A node that stops answering is given up on after a probe, not after the whole request timeout. */
class RemoteFailFastTest {

    private static NodeId id(int n) {
        return new NodeId(String.format("%064x", n));
    }

    @Test
    void aPeerThatIsGoneFailsTheRequestLongBeforeTheTimeout() {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            InMemoryMeshFabric fabric = new InMemoryMeshFabric();
            PeerCapabilities caps = new PeerCapabilities(true, false, Set.of());
            InMemoryMeshTransport server = new InMemoryMeshTransport(id(9), new ConcurrentEventBus(ex, (t, c) -> { }), fabric, true);
            InMemoryMeshTransport client = new InMemoryMeshTransport(id(1), new ConcurrentEventBus(ex, (t, c) -> { }), fabric, true);
            client.enroll(id(9), caps);
            server.enroll(id(1), caps);
            // the server is enrolled but nothing answers: no responder is running
            RemoteObjectStore store = new RemoteObjectStore(client, id(9)).timeout(Duration.ofSeconds(60))
                    .silenceBeforeProbe(Duration.ofMillis(300));
            long t0 = System.nanoTime();
            FsException e = assertThrows(FsException.class, () -> store.size(new com.redactado.noray.filesystem.store.ObjectId("a".repeat(64))));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(FsErrorCode.VOLUME_UNAVAILABLE, e.code());
            assertTrue(ms < 5_000, "gave up after " + ms + " ms");

            // circuit breaker: the next request does not wait for another probe
            long t1 = System.nanoTime();
            FsException again = assertThrows(FsException.class, () -> store.size(new com.redactado.noray.filesystem.store.ObjectId("c".repeat(64))));
            assertEquals(FsErrorCode.VOLUME_UNAVAILABLE, again.code());
            assertTrue((System.nanoTime() - t1) / 1_000_000 < 100, "breaker did not trip");

            // once the peer answers again (a health check finds it alive), requests go through
            BlobResponder responder = new BlobResponder(server, new InMemoryObjectStore(), AccessPolicy.allowAll(), 0, null);
            responder.start();
            assertTrue(store.alive(2_000));
            assertFalse(store.has(new com.redactado.noray.filesystem.store.ObjectId("d".repeat(64))));
            responder.close();
            store.close();
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void aSlowButAlivePeerIsWaitedFor() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            InMemoryMeshFabric fabric = new InMemoryMeshFabric();
            PeerCapabilities caps = new PeerCapabilities(true, false, Set.of());
            InMemoryMeshTransport server = new InMemoryMeshTransport(id(9), new ConcurrentEventBus(ex, (t, c) -> { }), fabric, true);
            InMemoryMeshTransport client = new InMemoryMeshTransport(id(1), new ConcurrentEventBus(ex, (t, c) -> { }), fabric, true);
            client.enroll(id(9), caps);
            server.enroll(id(1), caps);
            InMemoryObjectStore inner = new InMemoryObjectStore();
            com.redactado.noray.filesystem.store.ObjectStore backing = new com.redactado.noray.filesystem.store.ObjectStore() {
                @Override public com.redactado.noray.filesystem.store.PutResult put(byte[] d) { return inner.put(d); }
                @Override public byte[] get(com.redactado.noray.filesystem.store.ObjectId id) { return inner.get(id); }
                @Override public boolean has(com.redactado.noray.filesystem.store.ObjectId id) { return inner.has(id); }
                @Override public void delete(com.redactado.noray.filesystem.store.ObjectId id) { inner.delete(id); }
                @Override public Set<com.redactado.noray.filesystem.store.ObjectId> listIds() { return inner.listIds(); }
                @Override public long size(com.redactado.noray.filesystem.store.ObjectId id) {
                    try { Thread.sleep(1_500); } catch (InterruptedException ignored) { }
                    return 42;
                }
            };
            BlobResponder responder = new BlobResponder(server, backing, AccessPolicy.allowAll(), 0, null);
            responder.start();
            RemoteObjectStore store = new RemoteObjectStore(client, id(9)).timeout(Duration.ofSeconds(30))
                    .silenceBeforeProbe(Duration.ofMillis(300));
            assertEquals(42, store.size(new com.redactado.noray.filesystem.store.ObjectId("b".repeat(64))));
            store.close();
            responder.close();
        } finally {
            ex.shutdownNow();
        }
    }
}
