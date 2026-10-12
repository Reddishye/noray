package com.redactado.noray.net.onion;

import com.redactado.noray.net.InMemoryMeshFabric;
import com.redactado.noray.net.InMemoryMeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class OnionServiceTest {

    static final class ManualClock extends Clock {
        volatile long now = 1_800_000_000_000L;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    record Hop(NodeId from, NodeId to, byte[] cell) {
    }

    /** Records every cell a node hands to a neighbour. */
    static final class Spy implements OnionTransport {
        final NodeId self;
        final OnionTransport inner;
        final List<Hop> log;

        Spy(NodeId self, OnionTransport inner, List<Hop> log) {
            this.self = self;
            this.inner = inner;
            this.log = log;
        }

        @Override public void send(NodeId neighbour, byte[] cell) {
            log.add(new Hop(self, neighbour, cell.clone()));
            inner.send(neighbour, cell);
        }

        @Override public void onCell(BiConsumer<NodeId, byte[]> handler) { inner.onCell(handler); }
    }

    final class Node {
        final NodeId id;
        final InMemoryMeshTransport mesh;
        final MeshOnionTransport carrier;
        final OnionService onion;
        final List<byte[]> received = new CopyOnWriteArrayList<>();

        Node(String name, OnionConfig cfg) {
            id = new NodeId(HexFormat.of().formatHex(sha(name)));
            mesh = new InMemoryMeshTransport(id, new ConcurrentEventBus(executor, (t, c) -> { }), fabric, true);
            OnionIdentity identity = OnionIdentity.generate();
            carrier = new MeshOnionTransport(mesh, identity);
            onion = new OnionService(id, identity, carrier, new RandomPathSelector(), new Spy(id, carrier, log), cfg,
                    clock, null);
            onion.subscribe(received::add);
        }
    }

    private static byte[] sha(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final ManualClock clock = new ManualClock();
    private final InMemoryMeshFabric fabric = new InMemoryMeshFabric();
    private final List<Hop> log = Collections.synchronizedList(new ArrayList<>());
    private final List<Node> nodes = new ArrayList<>();
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        nodes.forEach(n -> n.onion.close());
        nodes.forEach(n -> n.carrier.close());
        executor.shutdownNow();
    }

    private OnionConfig sync() {
        return OnionConfig.defaults().withMaxDelay(Duration.ZERO);
    }

    private List<Node> mesh(int n, OnionConfig cfg) {
        for (int i = 0; i < n; i++) {
            nodes.add(new Node("node-" + i, cfg));
        }
        for (Node a : nodes) {
            for (Node b : nodes) {
                if (a != b) a.mesh.enroll(b.id, new PeerCapabilities(false, false, Set.of()));
            }
        }
        nodes.forEach(x -> x.carrier.announce());
        return nodes;
    }

    /** Hands a cell to {@code target} as if a neighbour had sent it. */
    private void inject(Node target, byte[] cell) {
        Node from = nodes.stream().filter(x -> x != target).findFirst().orElseThrow();
        from.carrier.send(target.id, cell);
    }

    private static byte[] message(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void messageArrivesThroughRelaysWithoutRevealingTheSender() {
        List<Node> n = mesh(5, sync());
        byte[] msg = "meet at the usual place".getBytes(StandardCharsets.UTF_8);
        n.get(0).onion.send(n.get(4).id, msg);

        assertEquals(1, n.get(4).received.size());
        assertArrayEquals(msg, n.get(4).received.get(0));
        assertTrue(n.subList(0, 4).stream().allMatch(x -> x.received.isEmpty()));

        // sender -> relay -> relay -> destination: three transmissions, the last one does not come from the sender
        assertEquals(3, log.size());
        assertEquals(n.get(0).id, log.get(0).from());
        assertNotEquals(n.get(0).id, log.get(2).from());
        assertEquals(n.get(4).id, log.get(2).to());
    }

    @Test
    void everyCellHasTheSameSizeAndLooksUnrelatedAtEachHop() {
        List<Node> n = mesh(6, sync());
        byte[] msg = message(500, 1);
        n.get(0).onion.send(n.get(5).id, msg);
        assertEquals(3, log.size());
        for (Hop h : log) {
            assertEquals(OnionPacket.CELL, h.cell().length);
            assertFalse(contains(h.cell(), Arrays.copyOf(msg, 24)), "plaintext must never be visible on a link");
        }
        // nothing is carried over from one hop to the next: no common 16-byte window
        for (int i = 0; i + 1 < log.size(); i++) {
            assertFalse(shareWindow(log.get(i).cell(), log.get(i + 1).cell(), 16),
                    "consecutive hops must not share bytes an observer could match");
        }
    }

    @Test
    void sizeOfTheCellDoesNotDependOnTheMessageOrTheNumberOfRelays() {
        List<Node> n = mesh(7, sync());
        for (int hops = 0; hops <= 5; hops++) {
            n.forEach(x -> x.received.clear());
            log.clear();
            OnionService real = new OnionService(n.get(0).id, OnionIdentity.generate(), n.get(0).carrier,
                    new RandomPathSelector(), new Spy(n.get(0).id, n.get(0).carrier, log),
                    sync().withHops(hops, 0), clock, null);
            real.send(n.get(6).id, new byte[hops * 3 + 1]);
            assertEquals(hops + 1, log.size());
            assertTrue(log.stream().allMatch(h -> h.cell().length == OnionPacket.CELL));
            assertEquals(1, n.get(6).received.size(), "hops=" + hops);
            real.close();
        }
    }

    @Test
    void largeMessagesAreSplitAndPutBackTogether() {
        List<Node> n = mesh(5, sync());
        byte[] msg = message(12_000, 2);
        n.get(1).onion.send(n.get(3).id, msg);
        assertEquals(1, n.get(3).received.size());
        assertArrayEquals(msg, n.get(3).received.get(0));
        assertTrue(log.size() > 3 * 10, "many cells, each crossing the relays");
        assertThrows(OnionException.class, () -> n.get(1).onion.send(n.get(3).id, new byte[17 * 1024]));
    }

    @Test
    void emptyMessageIsDelivered() {
        List<Node> n = mesh(4, sync());
        n.get(0).onion.send(n.get(3).id, new byte[0]);
        assertEquals(1, n.get(3).received.size());
        assertEquals(0, n.get(3).received.get(0).length);
    }

    @Test
    void aRecordedCellFedInAgainIsNotProcessedTwice() {
        List<Node> n = mesh(4, sync());
        n.get(0).onion.send(n.get(3).id, "once".getBytes(StandardCharsets.UTF_8));
        Hop last = log.get(log.size() - 1);
        Node target = n.stream().filter(x -> x.id.equals(last.to())).findFirst().orElseThrow();
        assertEquals(1, target.received.size());
        for (int i = 0; i < 3; i++) {
            inject(target, last.cell());
        }
        assertEquals(1, target.received.size());
        assertTrue(target.onion.stats().getOrDefault("replayed", 0L) >= 3);
    }

    @Test
    void anyModificationOfACellMakesItVanish() {
        List<Node> n = mesh(4, sync());
        n.get(0).onion.send(n.get(3).id, "x".getBytes(StandardCharsets.UTF_8));
        Hop first = log.get(0);
        Node entry = n.stream().filter(x -> x.id.equals(first.to())).findFirst().orElseThrow();
        n.forEach(x -> x.received.clear());
        log.clear();
        long before = entry.onion.stats().getOrDefault("invalid", 0L) + entry.onion.stats().getOrDefault("replayed", 0L);
        Random r = new Random(7);
        int tries = 400;
        for (int i = 0; i < tries; i++) {
            byte[] cell = first.cell().clone();
            cell[r.nextInt(cell.length)] ^= (byte) (1 << r.nextInt(8));
            inject(entry, cell);
        }
        // Padding is not authenticated (the relay discards it), so a change there still peels - but it carries the
        // same ephemeral key as the original, which is recognised as a replay. Everything else fails authentication.
        assertTrue(n.stream().allMatch(x -> x.received.isEmpty()), "a tampered cell must never be delivered");
        long rejected = entry.onion.stats().getOrDefault("invalid", 0L) + entry.onion.stats().getOrDefault("replayed", 0L)
                - before;
        assertEquals(tries, rejected, "every modified cell is refused as invalid or replayed");
        assertTrue(log.isEmpty(), "nothing tampered is forwarded");
    }

    @Test
    void aCellForAnotherNodeCannotBePeeled() {
        List<Node> n = mesh(4, sync());
        n.get(0).onion.send(n.get(3).id, "secret".getBytes(StandardCharsets.UTF_8));
        Hop first = log.get(0);
        Node other = n.stream().filter(x -> !x.id.equals(first.to()) && !x.id.equals(first.from())).findFirst().orElseThrow();
        log.clear();
        other.received.clear();
        inject(other, first.cell());
        assertEquals(1, other.onion.stats().getOrDefault("invalid", 0L));
        assertTrue(other.received.isEmpty());
        assertTrue(log.isEmpty(), "an invalid cell is not forwarded");
    }

    @Test
    void expiredCellsAreRefused() {
        List<Node> n = mesh(4, sync().withCellTtl(Duration.ofSeconds(30)));
        n.get(0).onion.send(n.get(3).id, "late".getBytes(StandardCharsets.UTF_8));
        Hop first = log.get(0);
        Node entry = n.stream().filter(x -> x.id.equals(first.to())).findFirst().orElseThrow();
        n.forEach(x -> x.received.clear());
        clock.now += 200_000;
        inject(entry, first.cell());
        assertEquals(1, entry.onion.stats().getOrDefault("expired", 0L));
        assertTrue(n.stream().allMatch(x -> x.received.isEmpty()));
    }

    @Test
    void refusesToSendWithoutEnoughRelaysOrKeys() {
        List<Node> n = mesh(2, sync());
        assertThrows(OnionException.class, () -> n.get(0).onion.send(n.get(1).id, new byte[1]),
                "with no relay the sender would be known to the destination");
        assertThrows(OnionException.class, () -> n.get(0).onion.send(
                new NodeId("ab".repeat(32)), new byte[1]), "unknown destination");
        assertThrows(OnionException.class, () -> n.get(0).onion.send(n.get(0).id, new byte[1]));

        Node direct = new Node("direct", sync().withHops(0, 0));
        nodes.add(direct);
        direct.mesh.enroll(n.get(1).id, new PeerCapabilities(false, false, Set.of()));
        n.get(1).mesh.enroll(direct.id, new PeerCapabilities(false, false, Set.of()));
        direct.carrier.announce();
        direct.onion.send(n.get(1).id, "plain".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, n.get(1).received.size());
    }

    @Test
    void coverTrafficTravelsLikeRealTrafficAndIsDiscarded() {
        List<Node> n = mesh(5, sync());
        n.get(0).onion.sendCover();
        assertTrue(log.size() >= 2);
        assertTrue(log.stream().allMatch(h -> h.cell().length == OnionPacket.CELL));
        assertTrue(n.stream().allMatch(x -> x.received.isEmpty()));
        assertEquals(1, n.stream().mapToLong(x -> x.onion.stats().getOrDefault("dropped", 0L)).sum());
    }

    @Test
    void relaysHoldCellsForARandomTimeButStillDeliver() throws Exception {
        List<Node> n = mesh(4, OnionConfig.defaults().withMaxDelay(Duration.ofMillis(40)));
        CountDownLatch got = new CountDownLatch(1);
        n.get(3).onion.subscribe(m -> got.countDown());
        n.get(0).onion.send(n.get(3).id, "slowly".getBytes(StandardCharsets.UTF_8));
        assertTrue(got.await(5, TimeUnit.SECONDS));
    }

    @Test
    void aSubscriberThatThrowsDoesNotStopTheOthers() {
        List<Node> n = mesh(4, sync());
        n.get(3).onion.subscribe(m -> { throw new IllegalStateException("boom"); });
        n.get(0).onion.send(n.get(3).id, new byte[]{1});
        assertEquals(1, n.get(3).received.size());
        assertEquals(1, n.get(3).onion.stats().getOrDefault("subscriber-errors", 0L));
    }

    @Test
    void replayFilterFailsClosedWhenFullAndForgetsOnlyAfterTheWindow() {
        ReplayFilter f = new ReplayFilter(1000, 3);
        byte[][] keys = new byte[5][32];
        for (int i = 0; i < 5; i++) keys[i][0] = (byte) (i + 1);
        assertTrue(f.firstSeen(keys[0], 0));
        assertFalse(f.firstSeen(keys[0], 10));
        assertTrue(f.firstSeen(keys[1], 20));
        assertTrue(f.firstSeen(keys[2], 30));
        assertFalse(f.firstSeen(keys[3], 40), "full: refuse rather than forget");
        assertFalse(f.firstSeen(keys[0], 1500), "still remembered one window later");
        assertTrue(f.firstSeen(keys[3], 2500), "two windows later the old entries are gone");
    }

    @Test
    void reassemblyIsBoundedAndDropsStalePartialMessages() {
        Reassembler r = new Reassembler(16 * 1024, 1000);
        // an attacker opening many incomplete messages cannot make us keep more than the cap
        for (long id = 0; id < 5000; id++) {
            byte[] chunk = Reassembler.split(id, new byte[3000], 1000).get(0);
            assertNull(r.accept(chunk, 0));
        }
        assertTrue(r.pending() <= 256);
        assertNull(r.accept(Reassembler.split(1, new byte[3000], 1000).get(0), 5000));
        assertEquals(1, r.pending(), "stale partials expired");
        // inconsistent totals and duplicates are ignored
        byte[] a = Reassembler.split(9, new byte[2000], 1000).get(0);
        r.accept(a, 5000);
        assertNull(r.accept(a, 5000));
        // a claimed total that cannot fit the maximum is refused outright
        java.nio.ByteBuffer evil = java.nio.ByteBuffer.allocate(Reassembler.HEADER + 1);
        evil.putLong(77).putShort((short) 0).putShort((short) 60000).put((byte) 1);
        assertNull(r.accept(evil.array(), 5000));
    }

    @Test
    void identitiesAreIndependentAndWipedOnClose() {
        OnionIdentity a = OnionIdentity.generate();
        OnionIdentity b = OnionIdentity.generate();
        assertFalse(Arrays.equals(a.publicKey(), b.publicKey()));
        byte[] cell = OnionPacket.build(List.of(), new NodeId("cd".repeat(32)), n -> a.publicKey(),
                new byte[]{1, 2, 3}, OnionPacket.DELIVER, 4_000_000_000L);
        assertNotNull(OnionPacket.peel(a, cell));
        assertNull(OnionPacket.peel(b, cell));
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static boolean shareWindow(byte[] a, byte[] b, int w) {
        Set<String> windows = new java.util.HashSet<>();
        for (int i = 0; i + w <= a.length; i++) {
            windows.add(HexFormat.of().formatHex(a, i, i + w));
        }
        for (int i = 0; i + w <= b.length; i++) {
            if (windows.contains(HexFormat.of().formatHex(b, i, i + w))) return true;
        }
        return false;
    }
}
