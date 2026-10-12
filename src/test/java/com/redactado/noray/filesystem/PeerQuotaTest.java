package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.remote.BlobResponder;
import com.redactado.noray.filesystem.store.remote.DeleteQuarantine;
import com.redactado.noray.filesystem.store.remote.PeerQuota;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** A peer can only occupy what the operator allows, however it behaves. */
class PeerQuotaTest {

    @TempDir Path tmp;

    private ExecutorService executor;
    private InMemoryObjectStore serverStore;
    private BlobResponder responder;
    private PeerQuota quota;
    private RemoteObjectStore alice;
    private RemoteObjectStore bob;
    private DeleteQuarantine quarantine;
    private final TestClock clock = new TestClock();

    private static NodeId id(int n) {
        return new NodeId(String.format("%064x", n));
    }

    private static byte[] bytes(int n, int fill) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) fill);
        return b;
    }

    private void start(boolean withQuarantine) {
        executor = Executors.newSingleThreadExecutor();
        InMemoryMeshFabric fabric = new InMemoryMeshFabric();
        PeerCapabilities caps = new PeerCapabilities(true, false, Set.of());
        InMemoryMeshTransport server = new InMemoryMeshTransport(id(9), new ConcurrentEventBus(executor, (t, c) -> { }), fabric, true);
        InMemoryMeshTransport a = new InMemoryMeshTransport(id(1), new ConcurrentEventBus(executor, (t, c) -> { }), fabric, true);
        InMemoryMeshTransport b = new InMemoryMeshTransport(id(2), new ConcurrentEventBus(executor, (t, c) -> { }), fabric, true);
        for (var t : new InMemoryMeshTransport[]{a, b}) {
            t.enroll(id(9), caps);
            server.enroll(t.localId(), caps);
        }
        serverStore = new InMemoryObjectStore();
        quarantine = withQuarantine ? new DeleteQuarantine(serverStore, clock, Duration.ofHours(1), null) : null;
        responder = new BlobResponder(server, serverStore, AccessPolicy.allowAll(), 0, quarantine);
        quota = new PeerQuota(100, tmp.resolve("ledger"), new byte[32]);
        responder.quota(quota);
        responder.start();
        alice = new RemoteObjectStore(a, id(9));
        bob = new RemoteObjectStore(b, id(9));
    }

    @AfterEach
    void tearDown() {
        if (alice == null) return;
        alice.close();
        bob.close();
        responder.close();
        executor.shutdownNow();
    }

    @Test
    void aPeerWithItsOwnLimitGetsThatLimit() {
        start(false);
        quota.override(id(1), 30);
        quota.override(id(2), 500);
        assertThrows(FsException.class, () -> alice.put(bytes(60, 1)));
        alice.put(bytes(30, 2));
        bob.put(bytes(400, 3));
        assertEquals(500, quota.limitFor(id(2)));
        assertEquals(100, quota.limitFor(id(7)), "everyone else keeps the default");
    }

    @Test
    void aPeerCannotExceedItsQuotaButOthersAreUnaffected() {
        start(false);
        alice.put(bytes(60, 1));
        FsException e = assertThrows(FsException.class, () -> alice.put(bytes(60, 2)));
        assertEquals(FsErrorCode.DENIED, e.code());
        assertEquals(1, serverStore.listIds().size(), "the refused blob was not stored");
        assertEquals(60, quota.used(id(1)));

        bob.put(bytes(90, 3));            // bob has his own budget
        assertEquals(90, quota.used(id(2)));
        assertEquals(60, quota.used(id(1)));
    }

    @Test
    void storingWhatIsAlreadyHereCostsNothing() {
        start(false);
        ObjectId blob = alice.put(bytes(80, 1)).id();
        bob.put(bytes(80, 1));            // the same content: free, and bob is not charged for alice's blob
        assertEquals(0, quota.used(id(2)));
        alice.put(bytes(80, 1));
        assertEquals(80, quota.used(id(1)));
        assertTrue(serverStore.has(blob));
    }

    @Test
    void deletingGivesTheSpaceBackToWhoPaidForIt() {
        start(false);
        ObjectId blob = alice.put(bytes(90, 1)).id();
        assertThrows(FsException.class, () -> alice.put(bytes(20, 2)));
        bob.delete(blob);                 // anybody with DELETE rights; the payer gets the space back
        assertEquals(0, quota.used(id(1)));
        alice.put(bytes(20, 2));
    }

    @Test
    void delayedDeletesReleaseWhenTheBlobIsReallyGone() {
        start(true);
        ObjectId blob = alice.put(bytes(90, 1)).id();
        alice.delete(blob);
        assertEquals(90, quota.used(id(1)), "still on disk, still counted");
        clock.advance(Duration.ofHours(2));
        quarantine.sweep();
        assertEquals(0, quota.used(id(1)));
    }

    @Test
    void reconcileDropsChargesForBlobsRemovedBehindItsBack() {
        start(false);
        ObjectId blob = alice.put(bytes(70, 1)).id();
        serverStore.delete(blob);         // local GC
        assertEquals(70, quota.used(id(1)));
        assertEquals(1, quota.reconcile(serverStore.listIds()));
        assertEquals(0, quota.used(id(1)));
    }

    @Test
    void theLedgerSurvivesRestartsAndIsSealed() throws Exception {
        start(false);
        alice.put(bytes(60, 1));
        quota.flush();
        byte[] raw = Files.readAllBytes(tmp.resolve("ledger"));
        assertFalse(new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1).contains(id(1).hex()),
                "peer ids are not visible on disk");

        PeerQuota reloaded = new PeerQuota(100, tmp.resolve("ledger"), new byte[32]);
        assertEquals(60, reloaded.used(id(1)));
        assertEquals(FsErrorCode.DENIED, assertThrows(FsException.class,
                () -> reloaded.reserve(id(1), new ObjectId("ab".repeat(32)), 50)).code());

        // wrong key or tampering stops it rather than starting with an empty ledger
        assertThrows(FsException.class, () -> new PeerQuota(100, tmp.resolve("ledger"), bytes(32, 7)));
    }

    @Test
    void plainLedgerWhenThereIsNoKeyring() {
        PeerQuota plain = new PeerQuota(100, tmp.resolve("plain"), null);
        plain.reserve(id(1), new ObjectId("cd".repeat(32)), 40);
        plain.flush();
        assertEquals(40, new PeerQuota(100, tmp.resolve("plain"), null).used(id(1)));
    }

    @Test
    void blobsFromBeforeTheLedgerAreReportedAndCanBeAttributed() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        ObjectId old1 = store.put(new byte[100]).id();
        store.put(new byte[50]);                         // two blobs nobody is charged for
        PeerQuota q = new PeerQuota(1_000, null, null);
        assertArrayEquals(new long[]{2, 150}, q.unattributed(store));
        NodeId peer = new NodeId("ab".repeat(32));
        q.reserve(peer, old1, 100);                      // a peer stores an old blob again: it pays for it
        assertArrayEquals(new long[]{1, 50}, q.unattributed(store));
        assertEquals(1, q.attributeUnclaimed(peer, store));
        assertArrayEquals(new long[]{0, 0}, q.unattributed(store));
        assertEquals(150, q.used(peer));
    }
}
