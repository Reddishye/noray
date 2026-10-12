package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.remote.BlobResponder;
import com.redactado.noray.filesystem.store.remote.DeleteQuarantine;
import com.redactado.noray.filesystem.store.remote.RemoteObjectStore;
import com.redactado.noray.identity.AccessPolicy;
import com.redactado.noray.identity.TestClock;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import com.redactado.noray.net.InMemoryMeshFabric;
import com.redactado.noray.net.InMemoryMeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** A delete by a peer can be undone until the delay runs out. */
class DeleteQuarantineTest {

    @TempDir Path tmp;

    private final TestClock clock = new TestClock();
    private ExecutorService executor;
    private InMemoryObjectStore serverStore;
    private DeleteQuarantine quarantine;
    private BlobResponder responder;
    private RemoteObjectStore remote;

    private static NodeId id(int n) {
        return new NodeId(String.format("%064x", n));
    }

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        InMemoryMeshFabric fabric = new InMemoryMeshFabric();
        InMemoryMeshTransport client = new InMemoryMeshTransport(id(1), new ConcurrentEventBus(executor, (t, c) -> { }), fabric, true);
        InMemoryMeshTransport server = new InMemoryMeshTransport(id(2), new ConcurrentEventBus(executor, (t, c) -> { }), fabric, true);
        PeerCapabilities caps = new PeerCapabilities(true, false, Set.of());
        client.enroll(id(2), caps);
        server.enroll(id(1), caps);
        serverStore = new InMemoryObjectStore();
        quarantine = new DeleteQuarantine(serverStore, clock, Duration.ofHours(24), tmp.resolve("q"));
        responder = new BlobResponder(server, serverStore, AccessPolicy.allowAll(), 0, quarantine);
        responder.start();
        remote = new RemoteObjectStore(client, id(2));
    }

    @AfterEach
    void tearDown() {
        remote.close();
        responder.close();
        executor.shutdownNow();
    }

    @Test
    void aRemoteDeleteHidesTheBlobButKeepsTheData() {
        ObjectId blob = remote.put("precious".getBytes()).id();
        remote.delete(blob);

        assertFalse(remote.has(blob));
        assertEquals(FsErrorCode.NOT_FOUND, assertThrows(FsException.class, () -> remote.get(blob)).code());
        assertEquals(FsErrorCode.NOT_FOUND, assertThrows(FsException.class, () -> remote.size(blob)).code());
        assertFalse(remote.listIds().contains(blob));
        assertEquals(FsErrorCode.NOT_FOUND, assertThrows(FsException.class, () -> remote.delete(blob)).code());
        assertTrue(serverStore.has(blob), "still on disk");
    }

    @Test
    void theOwnerCanRestoreEverythingDuringTheDelay() {
        ObjectId a = remote.put("a".getBytes()).id();
        ObjectId b = remote.put("b".getBytes()).id();
        remote.delete(a);
        remote.delete(b);
        assertEquals(2, quarantine.list().size());

        assertTrue(quarantine.restore(a));
        assertEquals(1, quarantine.restoreAll());
        assertArrayEquals("a".getBytes(), remote.get(a));
        assertArrayEquals("b".getBytes(), remote.get(b));
        assertFalse(quarantine.restore(a), "nothing left to restore");
    }

    @Test
    void afterTheDelayTheBlobIsReallyGone() {
        ObjectId blob = remote.put("temp".getBytes()).id();
        remote.delete(blob);
        clock.advance(Duration.ofHours(23));
        assertEquals(0, quarantine.sweep());
        assertTrue(serverStore.has(blob));
        clock.advance(Duration.ofHours(2));
        assertEquals(1, quarantine.sweep());
        assertFalse(serverStore.has(blob));
        assertTrue(quarantine.list().isEmpty());
    }

    @Test
    void storingTheSameContentAgainRevivesIt() {
        ObjectId blob = remote.put("same".getBytes()).id();
        remote.delete(blob);
        assertEquals(blob, remote.put("same".getBytes()).id());
        assertTrue(remote.has(blob));
        clock.advance(Duration.ofDays(3));
        quarantine.sweep();
        assertTrue(serverStore.has(blob), "the revived blob is not swept");
    }

    @Test
    void thePendingDeletesSurviveARestart() {
        ObjectId blob = remote.put("persist".getBytes()).id();
        remote.delete(blob);
        DeleteQuarantine reopened = new DeleteQuarantine(serverStore, clock, Duration.ofHours(24), tmp.resolve("q"));
        assertTrue(reopened.isHidden(blob));
        clock.advance(Duration.ofDays(2));
        assertEquals(1, reopened.sweep());
        assertFalse(serverStore.has(blob));
    }

    @Test
    void withoutADelayDeletesAreImmediate() {
        responder.close();
        InMemoryObjectStore store = new InMemoryObjectStore();
        DeleteQuarantine none = new DeleteQuarantine(store, clock, Duration.ZERO, null);
        InMemoryMeshFabric fabric = new InMemoryMeshFabric();
        InMemoryMeshTransport c = new InMemoryMeshTransport(id(3), new ConcurrentEventBus(executor, (t, x) -> { }), fabric, true);
        InMemoryMeshTransport s = new InMemoryMeshTransport(id(4), new ConcurrentEventBus(executor, (t, x) -> { }), fabric, true);
        PeerCapabilities caps = new PeerCapabilities(true, false, Set.of());
        c.enroll(id(4), caps);
        s.enroll(id(3), caps);
        try (BlobResponder r = new BlobResponder(s, store, AccessPolicy.allowAll(), 0, none);
             RemoteObjectStore rs = new RemoteObjectStore(c, id(4))) {
            r.start();
            ObjectId blob = rs.put("gone".getBytes()).id();
            rs.delete(blob);
            assertFalse(store.has(blob));
        }
    }

    @Test
    void aPeerCannotStoreMoreThanItsBudgetPerMinute() {
        responder.limitPuts(new com.redactado.noray.filesystem.store.remote.PeerRateLimiter(1000, clock));
        remote.put(new byte[600]);
        FsException e = assertThrows(FsException.class, () -> remote.put(new byte[500]));
        assertEquals(FsErrorCode.DENIED, e.code());
        remote.put(new byte[400]); // still fits in what is left of the minute
        clock.advance(Duration.ofSeconds(61));
        remote.put(new byte[900]); // a new minute, a new budget
    }
}
