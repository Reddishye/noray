package com.redactado.noray.identity;

import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.vfs.journal.*;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import com.redactado.noray.kernel.core.DefaultConfiguration;
import com.redactado.noray.net.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** The namespace survives the loss of the node that owns it: certified peers keep a copy only the owner can open. */
class NamespaceVaultTest {

    private final class Peer {
        final NodeId id;
        final InMemoryMeshTransport transport;
        final IdentityBootstrap.Managed managed;
        final Path dir;
        NamespaceVault vault;
        final boolean admitted;

        Peer(String name, boolean admitted) {
            dir = tmp.resolve(name);
            id = new NodeId(HexFormat.of().formatHex(sha(name)));
            transport = new InMemoryMeshTransport(id, new ConcurrentEventBus(executor, (t, c) -> { }), fabric, true);
            managed = IdentityBootstrap.create(new DefaultConfiguration(Map.of("noray.identity.authority.keys",
                    authority.publicKeyHex()), new ConcurrentEventBus(executor, (t, c) -> { })),
                    dir, null, transport, new byte[32], new byte[0], new TransportEnroller(transport, null), clock);
            all.add(this);
            this.admitted = admitted;
        }

        void admit() {
            Certificate c = managed.service().enrollmentRequest()
                    .toCertificate(Role.PEER.ops(), clock.instant().minusSeconds(30), clock.instant().plus(Duration.ofDays(30)), serial++)
                    .endorse(authority);
            managed.service().install(c);
        }

        NamespaceVault vault(byte[] key, List<NamespaceRecord> records) {
            vault = new NamespaceVault(transport, managed.service().directory(), key, dir.resolve("vault"),
                    () -> records, () -> new JournalState(records.size(), new byte[]{1}), 3);
            vault.start(Duration.ZERO);
            return vault;
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
    private final List<Peer> all = new ArrayList<>();
    private ExecutorService executor;
    private InMemoryMeshFabric fabric;
    private long serial = 1;
    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        fabric = new InMemoryMeshFabric();
    }

    @AfterEach
    void tearDown() {
        for (Peer p : all) {
            if (p.vault != null) p.vault.close();
            p.managed.exchange().close();
        }
        executor.shutdownNow();
    }

    private void mesh(Peer... peers) {
        for (Peer a : peers) for (Peer b : peers) if (a != b) {
            a.transport.enroll(b.id, new PeerCapabilities(true, false, Set.of()));
        }
        for (Peer p : peers) if (p.admitted) p.admit();
    }

    private static List<NamespaceRecord> namespace() {
        return List.of(
                new NamespaceRecord.Create("/mnt/docs", NodeType.DIRECTORY, 2, 1),
                new NamespaceRecord.Create("/mnt/docs/a.txt", NodeType.FILE, 3, 2),
                new NamespaceRecord.SetFile("/mnt/docs/a.txt", "ref-secret", "HOT"),
                new NamespaceRecord.SetPolicy("/mnt/docs", "cold"));
    }

    @Test
    void aLostNodeGetsItsNamespaceBackFromPeersThatCannotReadIt() throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 9);
        Peer owner = new Peer("owner", true), h1 = new Peer("h1", true), h2 = new Peer("h2", true);
        mesh(owner, h1, h2);
        h1.vault(new byte[32], List.of());
        h2.vault(new byte[32], List.of());
        List<NamespaceRecord> records = namespace();
        NamespaceVault v = owner.vault(key, records);

        assertEquals(2, v.push(true));
        assertEquals(List.of(owner.id), h1.vault.keptFor());
        for (Path f : Files.list(h1.dir.resolve("vault")).toList()) {
            assertFalse(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1).contains("ref-secret"),
                    "a holder sees only sealed bytes");
        }

        // the owner's disk is gone: a brand new vault with the same key (same passphrase) finds it again
        v.close();
        NamespaceVault fresh = new NamespaceVault(owner.transport, owner.managed.service().directory(), key,
                tmp.resolve("owner-new"), List::of, () -> new JournalState(0, new byte[0]), 3);
        fresh.start(Duration.ZERO);
        owner.vault = fresh;
        List<NamespaceRecord> back = fresh.recover(Duration.ofSeconds(3)).orElseThrow();
        assertEquals(records, back);

        NamespaceVault wrongKey = new NamespaceVault(owner.transport, owner.managed.service().directory(),
                new byte[32], tmp.resolve("owner-x"), List::of, () -> new JournalState(0, new byte[0]), 3);
        owner.vault = wrongKey;
        fresh.close();
        wrongKey.start(Duration.ZERO);
        assertTrue(wrongKey.recover(Duration.ofSeconds(1)).isEmpty(), "another passphrase opens nothing");
    }

    @Test
    void theNewestCopyWinsAndUncertifiedPeersAreNotServed() throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 4);
        Peer owner = new Peer("owner", true), holder = new Peer("holder", true), stranger = new Peer("stranger", false);
        mesh(owner, holder, stranger);
        holder.vault(new byte[32], List.of());
        List<NamespaceRecord> v1 = namespace();
        List<NamespaceRecord> current = new ArrayList<>(v1);
        NamespaceVault v = owner.vault(key, current);
        v.push(true);
        current.add(new NamespaceRecord.Unlink("/mnt/docs/a.txt"));
        v.push(true);
        List<NamespaceRecord> back = v.recover(Duration.ofSeconds(3)).orElseThrow();
        assertEquals(current, back, "the second push replaced the first");

        // a node without a certificate neither stores nor reads anything
        NamespaceVault s = stranger.vault(key, v1);
        s.push(true); // even if it tries, the holder refuses
        assertTrue(holder.vault.keptFor().stream().noneMatch(n -> n.equals(stranger.id)));
        assertTrue(s.recover(Duration.ofSeconds(1)).isEmpty());
    }
}
