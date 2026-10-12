package com.redactado.noray.identity;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.remote.BlobProtocol;
import com.redactado.noray.filesystem.store.remote.BlobResponder;
import com.redactado.noray.filesystem.store.remote.RemoteObjectStore;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import com.redactado.noray.kernel.core.DefaultConfiguration;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A small cluster where nodes only trust the authority's signature: what a compromised or unenrolled node can and
 * cannot do to the others.
 */
class ManagedClusterTest {

    /** One participant: transport, identity machinery and (for storage nodes) a responder over its own store. */
    private final class Node {
        final NodeId id;
        final InMemoryMeshTransport transport;
        final InMemoryObjectStore store = new InMemoryObjectStore();
        final IdentityBootstrap.Managed managed;
        final BlobResponder responder;

        Node(String name, Path dir, long maxPut) {
            id = new NodeId(HexFormat.of().formatHex(sha(name)));
            transport = new InMemoryMeshTransport(id, new ConcurrentEventBus(executor, (t, c) -> { }), fabric, true);
            nodes.add(this);
            managed = IdentityBootstrap.create(config(), dir.resolve(name), null, transport, new byte[32], new byte[0],
                    new TransportEnroller(transport, null), clock);
            responder = new BlobResponder(transport, store, managed.policy(), maxPut);
            responder.start();
        }

        void enroll(Node other) {
            transport.enroll(other.id, new PeerCapabilities(true, false, Set.of()));
        }

        /** Gets a certificate from the authority, as the operator would. */
        void admit(Role role, long serial) {
            Certificate c = managed.service().enrollmentRequest()
                    .toCertificate(role.ops(), clock.instant().minusSeconds(30), clock.instant().plus(Duration.ofDays(30)), serial)
                    .endorse(authority);
            managed.service().install(c);
        }

        RemoteObjectStore storeOf(Node server) {
            return new RemoteObjectStore(transport, server.id);
        }
    }

    private static byte[] sha(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private final TestClock clock = new TestClock();
    private final SigningKey authority = SigningKey.generate();
    private final List<Node> nodes = new ArrayList<>();
    private ExecutorService executor;
    private InMemoryMeshFabric fabric;
    @TempDir Path tmp;

    private DefaultConfiguration config() {
        return new DefaultConfiguration(Map.of("noray.identity.authority.keys", authority.publicKeyHex()),
                new ConcurrentEventBus(executor, (t, c) -> { }));
    }

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        fabric = new InMemoryMeshFabric();
    }

    @AfterEach
    void tearDown() {
        nodes.forEach(n -> { n.responder.close(); n.managed.exchange().close(); });
        executor.shutdownNow();
    }

    private static void assertDenied(Runnable r) {
        FsException e = assertThrows(FsException.class, r::run);
        assertEquals(FsErrorCode.DENIED, e.code(), e.getMessage());
    }

    private static void meshAll(Node... all) {
        for (Node a : all) for (Node b : all) if (a != b) a.enroll(b);
    }

    // ── what each role may do ──────────────────────────────────────────────────

    @Test
    void storageClientsCanReplicateButNeverDeleteOrEnumerate() {
        Node server = new Node("server", tmp, 0), client = new Node("client", tmp, 0), operator = new Node("operator", tmp, 0);
        meshAll(server, client, operator);
        server.admit(Role.PEER, 1);
        client.admit(Role.PEER, 1);
        operator.admit(Role.OPERATOR, 1);

        try (RemoteObjectStore asClient = client.storeOf(server); RemoteObjectStore asOperator = operator.storeOf(server)) {
            byte[] data = "ciphertext".getBytes();
            ObjectId id = asClient.put(data).id();
            assertArrayEquals(data, asClient.get(id));
            assertTrue(asClient.has(id));
            assertEquals(data.length, asClient.size(id));

            assertDenied(() -> asClient.delete(id));
            assertDenied(asClient::listIds);
            assertTrue(server.store.has(id), "the refused delete changed nothing");

            assertEquals(Set.of(id), asOperator.listIds());
            asOperator.delete(id);
            assertFalse(server.store.has(id));
            FsException gone = assertThrows(FsException.class, () -> asOperator.delete(id));
            assertEquals(FsErrorCode.NOT_FOUND, gone.code());
        }
    }

    @Test
    void aNodeWithoutACertificateGetsNothing() {
        Node server = new Node("server", tmp, 0), outsider = new Node("outsider", tmp, 0);
        meshAll(server, outsider);
        server.admit(Role.PEER, 1);
        ObjectId id = server.store.put("secret shard".getBytes()).id();

        try (RemoteObjectStore asOutsider = outsider.storeOf(server)) {
            assertDenied(() -> asOutsider.get(id));
            assertDenied(() -> asOutsider.put("x".getBytes()));
            assertDenied(() -> asOutsider.has(id));
            assertDenied(asOutsider::listIds);
            assertDenied(() -> asOutsider.delete(id));
        }
        assertEquals(Set.of(id), server.store.listIds());
        assertTrue(server.responder.denied() >= 5);
    }

    @Test
    void aCertificateIssuedByAnotherAuthorityIsWorthNothing() {
        Node server = new Node("server", tmp, 0), rogue = new Node("rogue", tmp, 0);
        meshAll(server, rogue);
        server.admit(Role.PEER, 1);
        SigningKey otherAuthority = SigningKey.generate();
        Certificate selfMade = rogue.managed.service().enrollmentRequest()
                .toCertificate(Role.OPERATOR.ops(), clock.instant().minusSeconds(30), clock.instant().plus(Duration.ofDays(30)), 1)
                .endorse(otherAuthority);
        assertDenied(() -> rogue.managed.service().install(selfMade)); // its own trust root refuses it too
        // and presenting it anyway, directly on the wire, gets it ignored
        rogue.transport.send(server.id, com.redactado.noray.net.MeshChannel.IDENTITY, concat((byte) 1, selfMade.encode()));
        assertFalse(server.managed.service().directory().knows(rogue.id));
        try (RemoteObjectStore asRogue = rogue.storeOf(server)) {
            assertDenied(asRogue::listIds);
        }
    }

    @Test
    void aStolenCertificateCannotBeUsedFromAnotherNode() {
        Node server = new Node("server", tmp, 0), victim = new Node("victim", tmp, 0), thief = new Node("thief", tmp, 0);
        meshAll(server, victim, thief);
        server.admit(Role.PEER, 1);
        victim.admit(Role.OPERATOR, 1);
        Certificate stolen = victim.managed.service().certificate().orElseThrow();

        thief.transport.send(server.id, com.redactado.noray.net.MeshChannel.IDENTITY, concat((byte) 1, stolen.encode()));
        try (RemoteObjectStore asThief = thief.storeOf(server)) {
            assertDenied(asThief::listIds);
        }
        assertEquals(PeerDirectory.Offer.REJECTED, server.managed.service().directory().offer(thief.id, stolen));
    }

    private static byte[] concat(byte first, byte[] rest) {
        byte[] out = new byte[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    // ── introductions ──────────────────────────────────────────────────────────

    @Test
    void firstRequestFromAnUnknownPeerTriggersTheIntroductionSoTheRetrySucceeds() {
        Node server = new Node("server", tmp, 0), client = new Node("client", tmp, 0);
        server.admit(Role.PEER, 1);
        client.admit(Role.PEER, 1); // nobody was connected yet: nothing was announced
        meshAll(server, client);

        try (RemoteObjectStore asClient = client.storeOf(server)) {
            assertDenied(() -> asClient.put("first".getBytes()));
            ObjectId id = asClient.put("second".getBytes()).id(); // now both know each other
            assertTrue(server.store.has(id));
        }
    }

    // ── revocation and expiry ──────────────────────────────────────────────────

    @Test
    void revokingACompromisedNodeCutsItOffEverywhereThroughGossip() {
        Node server = new Node("server", tmp, 0), other = new Node("other", tmp, 0), bad = new Node("bad", tmp, 0);
        meshAll(server, other, bad);
        server.admit(Role.PEER, 1);
        other.admit(Role.PEER, 1);
        bad.admit(Role.PEER, 1);
        try (RemoteObjectStore asBad = bad.storeOf(server); RemoteObjectStore asOther = other.storeOf(server)) {
            asBad.put("before".getBytes());

            // the operator tells ONE node; the others learn by gossip
            RevocationList list = RevocationList.unsigned(1, clock.millis(), Map.of(bad.id, 2L)).endorse(authority);
            assertTrue(other.managed.service().applyRevocations(list));

            assertDenied(() -> asBad.put("after".getBytes()));
            assertDenied(() -> asBad.get(ObjectId.class.cast(BlobProtocol.randomObjectId())));
            asOther.put("still fine".getBytes());
            assertEquals(1, server.managed.service().trust().revocations().version());
        }
    }

    @Test
    void aRevocationNotSignedByTheAuthorityIsIgnored() {
        Node server = new Node("server", tmp, 0), attacker = new Node("attacker", tmp, 0);
        meshAll(server, attacker);
        server.admit(Role.PEER, 1);
        RevocationList forged = RevocationList.unsigned(99, clock.millis(), Map.of(server.id, Long.MAX_VALUE))
                .endorse(SigningKey.generate());
        attacker.transport.send(server.id, com.redactado.noray.net.MeshChannel.IDENTITY, concat((byte) 2, forged.encode()));
        assertEquals(0, server.managed.service().trust().revocations().version());
        assertDenied(() -> attacker.managed.service().applyRevocations(forged));
    }

    @Test
    void certificatesExpireWithoutAnyoneRevokingThem() {
        Node server = new Node("server", tmp, 0), client = new Node("client", tmp, 0);
        meshAll(server, client);
        server.admit(Role.PEER, 1);
        client.admit(Role.PEER, 1);
        try (RemoteObjectStore asClient = client.storeOf(server)) {
            asClient.put("ok".getBytes());
            clock.advance(Duration.ofDays(31));
            assertDenied(() -> asClient.put("late".getBytes()));
        }
    }

    // ── limits and large listings ──────────────────────────────────────────────

    @Test
    void oversizedBlobsAreRefusedEvenForAuthorisedPeers() {
        Node server = new Node("server", tmp, 1024), client = new Node("client", tmp, 0);
        meshAll(server, client);
        server.admit(Role.PEER, 1);
        client.admit(Role.PEER, 1);
        try (RemoteObjectStore asClient = client.storeOf(server)) {
            asClient.put(new byte[1024]);
            assertDenied(() -> asClient.put(new byte[1025]));
        }
    }

    @Test
    void listingIsPagedAndCompleteForLargeStores() {
        Node server = new Node("server", tmp, 0), operator = new Node("operator", tmp, 0);
        meshAll(server, operator);
        server.admit(Role.PEER, 1);
        operator.admit(Role.OPERATOR, 1);
        int count = BlobProtocol.LIST_PAGE * 2 + 123;
        Set<ObjectId> expected = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            expected.add(server.store.put(("blob-" + i).getBytes()).id());
        }
        try (RemoteObjectStore asOperator = operator.storeOf(server)) {
            assertEquals(expected, asOperator.listIds());
        }
    }

    // ── admission by certificate ───────────────────────────────────────────────

    @Test
    void enrollingFromCertificatesIsEnoughToTalk() {
        Node server = new Node("server", tmp, 0), client = new Node("client", tmp, 0);
        server.admit(Role.PEER, 1);
        client.admit(Role.PEER, 1);
        assertFalse(server.transport.isEnrolled(client.id));

        server.managed.service().enroll(client.managed.service().certificate().orElseThrow(), "");
        client.managed.service().enroll(server.managed.service().certificate().orElseThrow(), "");
        assertTrue(server.transport.isEnrolled(client.id));

        try (RemoteObjectStore asClient = client.storeOf(server)) {
            assertTrue(server.store.has(asClient.put("hi".getBytes()).id()));
        }
        assertDenied(() -> server.managed.service().enroll(
                client.managed.service().enrollmentRequest().toCertificate(Role.OPERATOR.ops(),
                        clock.instant().minusSeconds(5), clock.instant().plus(Duration.ofDays(1)), 9), ""));
    }

    @Test
    void revocationAlsoClosesTheTransportDoor() {
        Node server = new Node("server", tmp, 0), bad = new Node("bad", tmp, 0);
        server.admit(Role.PEER, 1);
        bad.admit(Role.PEER, 1);
        server.managed.service().enroll(bad.managed.service().certificate().orElseThrow(), "");
        assertTrue(server.transport.isEnrolled(bad.id));

        server.managed.service().applyRevocations(
                RevocationList.unsigned(1, clock.millis(), Map.of(bad.id, 2L)).endorse(authority));
        assertFalse(server.transport.isEnrolled(bad.id));
        // a certificate issued after the revocation brings it back, through the same path
        bad.admit(Role.PEER, 5);
        server.managed.service().enroll(bad.managed.service().certificate().orElseThrow(), "");
        assertTrue(server.transport.isEnrolled(bad.id));
    }

    @Test
    void maintenanceForgetsExpiredCertificatesButKeepsThePeerReachable() {
        Node server = new Node("server", tmp, 0), client = new Node("client", tmp, 0);
        server.admit(Role.PEER, 1);
        client.admit(Role.PEER, 1);
        server.managed.service().enroll(client.managed.service().certificate().orElseThrow(), "");
        client.managed.service().enroll(server.managed.service().certificate().orElseThrow(), "");
        assertTrue(server.managed.service().directory().knows(client.id));

        clock.advance(Duration.ofDays(40));
        assertEquals(List.of(client.id), server.managed.maintenance().runOnce());
        assertFalse(server.managed.service().directory().knows(client.id));
        assertTrue(server.transport.isEnrolled(client.id), "expiry is not revocation: a renewal can still arrive");

        // the renewed certificate is accepted over the mesh with no manual step
        client.admit(Role.PEER, 2);
        server.admit(Role.PEER, 2);
        try (RemoteObjectStore asClient = client.storeOf(server)) {
            assertTrue(server.managed.service().directory().knows(client.id), "installing announced the renewal");
            assertTrue(server.store.has(asClient.put("renewed".getBytes()).id()));
        }
    }

    // ── garbage collection across the network ──────────────────────────────────

    @Test
    void remoteGarbageCollectionNeedsTheOperatorRightsAndKeepsLiveData() throws Exception {
        List<Node> servers = new ArrayList<>();
        Node operator = new Node("operator", tmp, 0);
        Node plainClient = new Node("plain-client", tmp, 0);
        operator.admit(Role.OPERATOR, 1);
        plainClient.admit(Role.PEER, 1);
        for (int i = 0; i < 3; i++) {
            Node s = new Node("s" + i, tmp, 0);
            s.admit(Role.PEER, 1);
            meshAll(s, operator, plainClient);
            servers.add(s);
        }
        meshAll(operator, plainClient);
        nodes.forEach(n -> n.managed.exchange().announceAll()); // everyone introduces itself to the peers it now has

        List<RemoteObjectStore> opStores = new ArrayList<>(), clientStores = new ArrayList<>();
        List<com.redactado.noray.data.placement.SimpleNode> opNodes = new ArrayList<>(), clientNodes = new ArrayList<>();
        for (Node s : servers) {
            opStores.add(operator.storeOf(s));
            clientStores.add(plainClient.storeOf(s));
            opNodes.add(new com.redactado.noray.data.placement.SimpleNode(s.id.hex().substring(0, 8), opStores.get(opStores.size() - 1)));
            clientNodes.add(new com.redactado.noray.data.placement.SimpleNode(s.id.hex().substring(0, 8), clientStores.get(clientStores.size() - 1)));
        }
        var policy = com.redactado.noray.data.StoragePolicy.single().withMetadataReplicas(2);
        try (var engine = new com.redactado.noray.data.DataEngine(com.redactado.noray.data.placement.DataCluster.of(opNodes), policy);
             var clientEngine = new com.redactado.noray.data.DataEngine(com.redactado.noray.data.placement.DataCluster.of(clientNodes), policy)) {
            byte[] data = new byte[200_000];
            new java.util.Random(7).nextBytes(data);
            var ref = engine.put(data);
            Set<ObjectId> orphans = new java.util.HashSet<>();
            for (int i = 0; i < 6; i++) {
                orphans.add(servers.get(i % 3).store.put(("orphan-" + i).getBytes()).id());
            }
            Thread.sleep(5);

            // a plain client may not enumerate: its GC skips every node and deletes nothing
            var refused = clientEngine.collectGarbage(engine.liveBlobs(ref), Duration.ZERO, false);
            assertEquals(0, refused.blobsDeleted());
            assertEquals(3, refused.nodesSkipped().size());
            assertEquals(6, servers.stream().flatMap(s -> s.store.listIds().stream()).filter(orphans::contains).count());

            var done = engine.collectGarbage(engine.liveBlobs(ref), Duration.ZERO, false);
            assertEquals(6, done.blobsDeleted());
            assertEquals(0, servers.stream().flatMap(s -> s.store.listIds().stream()).filter(orphans::contains).count());
            assertArrayEquals(data, engine.getAll(ref), "live data is untouched");
        } finally {
            opStores.forEach(RemoteObjectStore::close);
            clientStores.forEach(RemoteObjectStore::close);
        }
    }
}
