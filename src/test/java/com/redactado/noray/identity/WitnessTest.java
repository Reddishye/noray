package com.redactado.noray.identity;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.vfs.journal.JournalState;
import com.redactado.noray.identity.log.SignedLog;
import com.redactado.noray.identity.witness.WitnessService;
import com.redactado.noray.identity.witness.WitnessService.AlarmKind;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import com.redactado.noray.kernel.core.DefaultConfiguration;
import com.redactado.noray.net.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Restoring an old copy of a node's data is invisible to the node, but not to the peers that witnessed it. */
class WitnessTest {

    private static byte[] hash(int n) {
        byte[] h = new byte[16];
        Arrays.fill(h, (byte) n);
        return h;
    }

    private final class Peer {
        final NodeId id;
        final InMemoryMeshTransport transport;
        final IdentityBootstrap.Managed managed;
        final Path dir;
        final byte[] seal;
        final AtomicReference<JournalState> journal = new AtomicReference<>(new JournalState(0, hash(0)));
        WitnessService witness;

        Peer(String name, Role role, byte[] seal) {
            this.seal = seal;
            this.dir = tmp.resolve(name);
            id = new NodeId(HexFormat.of().formatHex(sha(name)));
            transport = new InMemoryMeshTransport(id, new ConcurrentEventBus(executor, (t, c) -> { }), fabric, true);
            managed = IdentityBootstrap.create(config(), dir, null, transport, new byte[32], new byte[0],
                    new TransportEnroller(transport, null), clock);
            all.add(this);
            this.role = role;
        }

        final Role role;

        void admit(long serial) {
            Certificate c = managed.service().enrollmentRequest()
                    .toCertificate(role.ops(), clock.instant().minusSeconds(30), clock.instant().plus(Duration.ofDays(30)), serial)
                    .endorse(authority);
            managed.service().install(c);
        }

        Path witnessDir() { return dir.resolve("witness"); }

        WitnessService startWitness() {
            return startWitness(20_000);
        }

        WitnessService startWitness(int rotateAt) {
            witness = new WitnessService(transport, managed.service().directory(), managed.service().identity().signingKey(),
                    witnessDir(), seal, clock, 5, rotateAt);
            witness.bind(journal::get);
            witness.start(Duration.ZERO);
            return witness;
        }

        void state(long records, int h) { journal.set(new JournalState(records, hash(h))); }
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
        for (Peer p : all) {
            if (p.witness != null) p.witness.close();
            p.managed.exchange().close();
        }
        executor.shutdownNow();
    }

    private void mesh(Peer... peers) {
        for (Peer a : peers) for (Peer b : peers) if (a != b) {
            a.transport.enroll(b.id, new PeerCapabilities(true, false, Set.of()));
        }
        long serial = 1;
        for (Peer p : peers) p.admit(serial++);
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> s = Files.list(from)) {
            for (Path f : (Iterable<Path>) s::iterator) Files.copy(f, to.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean has(WitnessService w, AlarmKind kind) {
        return w.alarms().stream().anyMatch(a -> a.kind() == kind);
    }

    @Test
    void aRevokedPeersLogIsDroppedAndNotTakenAgain() throws Exception {
        Peer owner = new Peer("owner", Role.PEER, null), w1 = new Peer("w1", Role.PEER, null);
        mesh(owner, w1);
        WitnessService o = owner.startWitness();
        w1.startWitness();
        w1.managed.service().onRevoked(w1.witness::forget);
        owner.state(5, 1);
        o.runOnce();
        assertEquals(2, w1.witness.held().get(owner.id));
        Path file = w1.witnessDir().resolve("held-" + owner.id.hex() + ".log");
        assertTrue(Files.exists(file));

        w1.managed.service().acceptRevocations(com.redactado.noray.identity.RevocationList
                .unsigned(1, clock.millis(), Map.of(owner.id, 2L)).endorse(authority));

        assertFalse(w1.witness.held().containsKey(owner.id));
        assertFalse(Files.exists(file));
        owner.state(9, 2);
        o.runOnce();
        assertFalse(w1.witness.held().containsKey(owner.id), "a revoked node cannot have its history kept");
    }

    @Test
    void checkpointsReachWitnessesAndQuietHistoryRaisesNothing() {
        Peer owner = new Peer("owner", Role.PEER, null), w1 = new Peer("w1", Role.PEER, null), w2 = new Peer("w2", Role.PEER, null);
        mesh(owner, w1, w2);
        WitnessService o = owner.startWitness();
        w1.startWitness();
        w2.startWitness();

        owner.state(5, 1);
        o.runOnce();
        owner.state(9, 2);
        o.runOnce();

        assertEquals(3, w1.witness.held().get(owner.id), "genesis + two checkpoints");
        assertEquals(3, w2.witness.held().get(owner.id));
        assertEquals(3, o.witnessAcknowledged().get(w1.id));
        assertTrue(o.alarms().isEmpty());
        assertTrue(w1.witness.alarms().isEmpty());
        o.runOnce();                      // nothing changed: nothing new is recorded
        assertEquals(2, o.ownCheckpoints());
    }

    @Test
    void aRestoredBackupIsNoticedByThePeersThatKeptTheNewerHistory() throws Exception {
        Peer owner = new Peer("owner", Role.PEER, null), w1 = new Peer("w1", Role.PEER, null);
        mesh(owner, w1);
        WitnessService o = owner.startWitness();
        w1.startWitness();

        owner.state(5, 1);
        o.runOnce();
        copy(owner.witnessDir(), tmp.resolve("backup"));   // the attacker's old copy
        owner.state(8, 2);
        o.runOnce();
        owner.state(12, 3);
        o.runOnce();
        o.close();

        // restore: the data directory is exactly what it was after the first checkpoint
        try (Stream<Path> s = Files.list(owner.witnessDir())) {
            for (Path f : (Iterable<Path>) s::iterator) Files.delete(f);
        }
        copy(tmp.resolve("backup"), owner.witnessDir());
        owner.state(5, 1);
        WitnessService restored = owner.startWitness();
        assertTrue(restored.alarms().isEmpty(), "locally everything looks consistent");

        restored.runOnce();
        assertTrue(has(restored, AlarmKind.LOG_ROLLED_BACK), restored.alarms().toString());
        assertTrue(restored.alarms().get(0).detail().contains("12 records"), "tells what was lost: " + restored.alarms());
    }

    @Test
    void aNodeThatContinuesAnOldCopyDifferentlyIsCaughtByBothSides() throws Exception {
        Peer owner = new Peer("owner", Role.PEER, null), w1 = new Peer("w1", Role.PEER, null);
        mesh(owner, w1);
        WitnessService o = owner.startWitness();
        w1.startWitness();

        owner.state(5, 1);
        o.runOnce();
        copy(owner.witnessDir(), tmp.resolve("backup"));
        owner.state(8, 2);
        o.runOnce();
        o.close();

        try (Stream<Path> s = Files.list(owner.witnessDir())) {
            for (Path f : (Iterable<Path>) s::iterator) Files.delete(f);
        }
        copy(tmp.resolve("backup"), owner.witnessDir());
        owner.state(8, 9);                              // same count, other history
        WitnessService restored = owner.startWitness();
        restored.runOnce();
        restored.runOnce();

        assertTrue(has(w1.witness, AlarmKind.PEER_FORKED), w1.witness.alarms().toString());
        assertTrue(has(restored, AlarmKind.LOG_FORKED), restored.alarms().toString());
        assertEquals(3, w1.witness.held().get(owner.id), "the witness keeps the first history");
    }

    @Test
    void aJournalOlderThanItsLastCheckpointIsCaughtAtStart() {
        Peer owner = new Peer("owner", Role.PEER, null);
        mesh(owner);
        WitnessService o = owner.startWitness();
        owner.state(20, 4);
        o.runOnce();
        o.close();

        owner.state(15, 3);                             // only the journal was rolled back
        WitnessService again = owner.startWitness();
        assertTrue(has(again, AlarmKind.JOURNAL_ROLLED_BACK), again.alarms().toString());

        owner.state(20, 7);                             // as many records, other content
        again.close();
        WitnessService third = owner.startWitness();
        assertTrue(has(third, AlarmKind.JOURNAL_REWRITTEN), third.alarms().toString());
    }

    @Test
    void anAlarmAboutOurOwnHistoryMakesAGuardedJournalReadOnlyUntilCleared() {
        Peer owner = new Peer("owner", Role.PEER, null);
        mesh(owner);
        WitnessService o = owner.startWitness();
        owner.state(20, 4);
        o.runOnce();
        o.close();
        owner.state(15, 3);
        WitnessService again = owner.startWitness();
        assertNotNull(again.integrityAlarm());

        var inner = new java.util.concurrent.atomic.AtomicInteger();
        var guarded = new com.redactado.noray.filesystem.vfs.journal.GuardedJournal(
                com.redactado.noray.filesystem.vfs.journal.NamespaceJournal.none(), again::integrityAlarm);
        var record = new com.redactado.noray.filesystem.vfs.journal.NamespaceRecord.Unlink("/x");
        FsException e = assertThrows(FsException.class, () -> guarded.append(record));
        assertEquals(FsErrorCode.DENIED, e.code());
        assertThrows(FsException.class, () -> guarded.rewrite(java.util.List.of()));
        assertTrue(guarded.load().isEmpty(), "reading is still allowed");

        again.clearAlarms();
        guarded.append(record); // the operator looked at it and decided
    }

    @Test
    void compactionIsNotMistakenForLoss() {
        Peer owner = new Peer("owner", Role.PEER, null);
        mesh(owner);
        WitnessService o = owner.startWitness();
        owner.state(500, 4);
        o.runOnce();
        o.beforeRewrite();                              // a crash here must not look like a rollback
        o.close();
        owner.state(40, 6);
        WitnessService crashed = owner.startWitness();
        assertTrue(crashed.alarms().isEmpty(), crashed.alarms().toString());

        owner.state(40, 6);
        crashed.afterRewrite();
        crashed.close();
        WitnessService next = owner.startWitness();
        assertTrue(next.alarms().isEmpty(), "the new, smaller state is the reference now: " + next.alarms());
        owner.state(30, 1);
        next.close();
        assertTrue(has(owner.startWitness(), AlarmKind.JOURNAL_ROLLED_BACK));
    }

    @Test
    void aWitnessCannotInventEvidence() {
        Peer owner = new Peer("owner", Role.PEER, null), liar = new Peer("liar", Role.PEER, null);
        mesh(owner, liar);
        WitnessService o = owner.startWitness();
        owner.state(5, 1);
        o.runOnce();

        // an entry far ahead of our log, but signed by somebody else
        SignedLog fake = SignedLog.create(SigningKey.generate(), "x", clock);
        byte[] frame = SignedLog.encodeEntries(List.of(fake.head()));
        byte[] withKind = new byte[frame.length + 1];
        withKind[0] = 2; // ACK
        System.arraycopy(frame, 0, withKind, 1, frame.length);
        liar.transport.send(owner.id, MeshChannel.WITNESS, withKind);
        assertTrue(o.alarms().isEmpty());

        // garbage is dropped
        liar.transport.send(owner.id, MeshChannel.WITNESS, new byte[]{1, 0, 0});
        liar.transport.send(owner.id, MeshChannel.WITNESS, new byte[]{9, 1, 2, 3});
        assertTrue(o.alarms().isEmpty());
    }

    @Test
    void onlyCertifiedPeersWithStoreRightsAreWitnessedOrWitnesses() {
        Peer owner = new Peer("owner", Role.PEER, null);
        Peer reader = new Peer("reader", Role.READER, null);
        Peer stranger = new Peer("stranger", Role.PEER, null);
        mesh(owner, reader);                              // the stranger never gets a certificate
        stranger.transport.enroll(owner.id, new PeerCapabilities(true, false, Set.of()));
        owner.transport.enroll(stranger.id, new PeerCapabilities(true, false, Set.of()));
        WitnessService o = owner.startWitness();
        reader.startWitness();
        stranger.startWitness();

        owner.state(5, 1);
        o.runOnce();
        assertTrue(reader.witness.held().isEmpty(), "a reader may not store anything here");
        assertTrue(stranger.witness.held().isEmpty());

        // and a certified owner is not witnessed by a node that has no rights: the stranger cannot publish
        stranger.state(1, 1);
        stranger.witness.runOnce();
        assertTrue(owner.witness.held().isEmpty());
    }

    @Test
    void witnessedHistoriesAndTheOwnLogSurviveARestartAndAreSealed() throws Exception {
        byte[] seal = new byte[32];
        Arrays.fill(seal, (byte) 5);
        Peer owner = new Peer("owner", Role.PEER, seal), w1 = new Peer("w1", Role.PEER, seal);
        mesh(owner, w1);
        WitnessService o = owner.startWitness();
        w1.startWitness();
        owner.state(5, 1);
        o.runOnce();
        w1.witness.close();

        WitnessService reloaded = w1.startWitness();
        assertEquals(2, reloaded.held().get(owner.id));

        byte[] raw = Files.readAllBytes(owner.witnessDir().resolve("own.log"));
        assertFalse(new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1).contains("noray/namespace-checkpoints"),
                "the log is not readable on disk");

        // a wrong key or a damaged file stops the node instead of silently starting a new history
        o.close();
        byte[] other = new byte[32];
        assertThrows(FsException.class, () -> new WitnessService(owner.transport, owner.managed.service().directory(),
                owner.managed.service().identity().signingKey(), owner.witnessDir(), other, clock, 5));
        Files.write(owner.witnessDir().resolve("own.log"), new byte[]{1, 2, 3});
        FsException e = assertThrows(FsException.class, owner::startWitness);
        assertNotEquals(FsErrorCode.NOT_FOUND, e.code());
    }

    @Test
    void anOwnLogOfAnotherKeyIsNeverAdopted() throws Exception {
        Peer owner = new Peer("owner", Role.PEER, null);
        mesh(owner);
        Files.createDirectories(owner.witnessDir());
        SignedLog foreign = SignedLog.create(SigningKey.generate(), "noray/namespace-checkpoints/v1", clock);
        Files.write(owner.witnessDir().resolve("own.log"), foreign.encode());
        FsException e = assertThrows(FsException.class, owner::startWitness);
        assertEquals(FsErrorCode.DENIED, e.code());
    }

    @Test
    void theCheckpointLogRotatesAndWitnessesFollowTheChain() {
        Peer owner = new Peer("owner", Role.PEER, null), w1 = new Peer("w1", Role.PEER, null);
        mesh(owner, w1);
        WitnessService o = owner.startWitness(5);
        w1.startWitness();
        for (int i = 1; i <= 12; i++) {
            owner.state(i, i);
            o.runOnce();
        }
        assertTrue(o.ownCheckpoints() < 6, "a new, short log was started: " + o.ownCheckpoints());
        assertTrue(Files.exists(owner.witnessDir().resolve("own.log.prev")));
        assertTrue(o.alarms().isEmpty(), o.alarms().toString());
        assertTrue(w1.witness.alarms().isEmpty(), w1.witness.alarms().toString());
        assertEquals(o.ownCheckpoints() + 1, w1.witness.held().get(owner.id));
        assertEquals(o.ownCheckpoints() + 1, o.witnessAcknowledged().get(w1.id));

        // and it survives a restart of the owner
        o.close();
        WitnessService again = owner.startWitness(5);
        again.runOnce();
        assertTrue(again.alarms().isEmpty(), again.alarms().toString());
    }

    @Test
    void aWitnessThatWasAwayDuringARotationCatchesUpWithoutFalseAlarms() {
        Peer owner = new Peer("owner", Role.PEER, null), w1 = new Peer("w1", Role.PEER, null), w2 = new Peer("w2", Role.PEER, null);
        mesh(owner, w1, w2);
        WitnessService o = owner.startWitness(5);
        w1.startWitness();
        w2.startWitness();
        owner.state(1, 1);
        o.runOnce();
        w2.witness.close();                      // w2 goes away
        for (int i = 2; i <= 6; i++) {
            owner.state(i, i);
            o.runOnce();
        }
        w2.startWitness();                       // ...and comes back, reloading what it had
        for (int i = 0; i < 4; i++) o.runOnce();

        assertTrue(o.alarms().isEmpty(), o.alarms().toString());
        assertTrue(w2.witness.alarms().isEmpty(), w2.witness.alarms().toString());
        assertEquals(w1.witness.held().get(owner.id), w2.witness.held().get(owner.id), "on the same, new log");
    }

    @Test
    void anOldEpochRestoredAfterARotationIsStillCaught() throws Exception {
        Peer owner = new Peer("owner", Role.PEER, null), w1 = new Peer("w1", Role.PEER, null);
        mesh(owner, w1);
        WitnessService o = owner.startWitness(5);
        w1.startWitness();
        for (int i = 1; i <= 3; i++) {
            owner.state(i, i);
            o.runOnce();
        }
        copy(owner.witnessDir(), tmp.resolve("backup"));        // first epoch
        for (int i = 4; i <= 12; i++) {
            owner.state(i, i);
            o.runOnce();
        }
        o.close();
        try (Stream<Path> s = Files.list(owner.witnessDir())) {
            for (Path f : (Iterable<Path>) s::iterator) Files.delete(f);
        }
        copy(tmp.resolve("backup"), owner.witnessDir());
        owner.state(3, 3);
        WitnessService restored = owner.startWitness(5);
        restored.runOnce();
        assertFalse(restored.alarms().isEmpty(), "the witness holds a later epoch");
        assertTrue(has(w1.witness, AlarmKind.PEER_FORKED) || has(restored, AlarmKind.LOG_ROLLED_BACK)
                || has(restored, AlarmKind.LOG_FORKED));
    }
}
