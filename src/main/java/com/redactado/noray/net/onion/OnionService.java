package com.redactado.noray.net.onion;

import com.redactado.noray.net.NodeId;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Anonymous, best-effort message delivery over relays.
 *
 * <p>{@link #send} wraps a message in one layer per relay and for the destination; each relay peels its layer,
 * waits a random moment and hands the rest on, knowing only its neighbours. The destination receives the message
 * without learning who sent it ({@link #subscribe}); an application that needs to know must put a signature in the
 * message. Delivery is not reliable (cells can be lost, nothing is retransmitted) and there is no way to reply.
 *
 * <p>What it protects against: a relay, or the destination, linking the sender to the message; a network observer
 * reading routes (the mesh link is encrypted as well). What it does not: an observer who sees both ends and
 * correlates timing and volume; a sender that is the only node of the network; the first relay knowing who talks
 * to it. Use {@link #sendCover} to blur volume.
 *
 * <p>The three things that vary are plug-ins: {@link OnionDirectory} (who can be a hop), {@link PathSelector}
 * (which ones to use) and {@link OnionTransport} (how a cell reaches a neighbour).
 */
public final class OnionService implements AutoCloseable {

    private static final long SKEW_SECONDS = 60;

    private final NodeId self;
    private final OnionIdentity identity;
    private final OnionDirectory directory;
    private final PathSelector selector;
    private final OnionTransport transport;
    private final OnionConfig config;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final SecureRandom random = new SecureRandom();
    private final ReplayFilter replays;
    private final Reassembler reassembler;
    private final List<Consumer<byte[]>> subscribers = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicLong> counters = new java.util.concurrent.ConcurrentHashMap<>();

    public OnionService(NodeId self, OnionIdentity identity, OnionDirectory directory, PathSelector selector,
                        OnionTransport transport, OnionConfig config, Clock clock,
                        ScheduledExecutorService scheduler) {
        this.self = Objects.requireNonNull(self);
        this.identity = Objects.requireNonNull(identity);
        this.directory = Objects.requireNonNull(directory);
        this.selector = Objects.requireNonNull(selector);
        this.transport = Objects.requireNonNull(transport);
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.ownsScheduler = scheduler == null;
        this.scheduler = scheduler != null ? scheduler : Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "onion-forward");
            t.setDaemon(true);
            return t;
        });
        this.replays = new ReplayFilter(config.cellTtl().toMillis() + SKEW_SECONDS * 1000, config.replayEntries());
        this.reassembler = new Reassembler(config.maxMessage(), config.reassembly().toMillis());
        transport.onCell((from, cell) -> receive(cell));
    }

    /** Called with every message addressed to this node. */
    public void subscribe(Consumer<byte[]> handler) {
        subscribers.add(Objects.requireNonNull(handler));
    }

    /** Sends {@code message} to {@code destination} through random relays. */
    public void send(NodeId destination, byte[] message) throws OnionException {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(message, "message");
        if (message.length > config.maxMessage()) {
            throw new OnionException("message of " + message.length + " bytes exceeds the limit of "
                    + config.maxMessage());
        }
        if (destination.equals(self)) {
            throw new OnionException("cannot send an onion message to ourselves");
        }
        byte[] destKey = directory.keyOf(destination).orElseThrow(
                () -> new OnionException("no onion key known for " + destination));
        List<NodeId> relays = selector.select(self, destination, config.hops(), directory.relays());
        if (relays.size() < config.minHops()) {
            throw new OnionException("only " + relays.size() + " relay(s) available, " + config.minHops()
                    + " required");
        }
        int chunk = OnionPacket.capacity(relays.size()) - Reassembler.HEADER;
        long expiry = clock.instant().getEpochSecond() + config.cellTtl().toSeconds();
        List<byte[]> chunks = Reassembler.split(random.nextLong(), message, chunk);
        NodeId first = relays.isEmpty() ? destination : relays.get(0);
        for (byte[] c : chunks) {
            byte[] cell = OnionPacket.build(relays, destination, n -> n.equals(destination) ? destKey : keyOf(n), c,
                    OnionPacket.DELIVER, expiry);
            sendCell(first, cell);
            count("sent");
        }
    }

    /** Sends a cell that travels like a real message and is discarded by its last relay. */
    public void sendCover() throws OnionException {
        List<NodeId> candidates = selector.select(self, new NodeId("0".repeat(64)), config.hops() + 1,
                directory.relays());
        if (candidates.size() < Math.max(1, config.minHops() + 1)) {
            throw new OnionException("not enough relays for cover traffic");
        }
        NodeId end = candidates.get(candidates.size() - 1);
        List<NodeId> relays = candidates.subList(0, candidates.size() - 1);
        byte[] filler = new byte[Reassembler.HEADER];
        random.nextBytes(filler);
        long expiry = clock.instant().getEpochSecond() + config.cellTtl().toSeconds();
        byte[] cell = OnionPacket.build(relays, end, this::keyOf, filler, OnionPacket.DROP, expiry);
        sendCell(relays.isEmpty() ? end : relays.get(0), cell);
        count("cover");
    }

    private byte[] keyOf(NodeId n) {
        return directory.keyOf(n).orElse(null);
    }

    private void sendCell(NodeId neighbour, byte[] cell) {
        try {
            transport.send(neighbour, cell);
        } catch (RuntimeException e) {
            throw new OnionException("cannot reach first hop " + neighbour + ": " + e.getMessage());
        }
    }

    private void receive(byte[] cell) {
        OnionPacket.Peeled p = OnionPacket.peel(identity, cell);
        if (p == null) {
            count("invalid");
            return;
        }
        long now = clock.millis();
        long nowSec = now / 1000;
        if (p.expirySeconds() + SKEW_SECONDS < nowSec) {
            count("expired");
            return;
        }
        if (p.expirySeconds() > nowSec + config.cellTtl().toSeconds() + SKEW_SECONDS) {
            count("invalid");
            return;
        }
        if (!replays.firstSeen(p.ephemeral(), now)) {
            count("replayed");
            return;
        }
        switch (p.command()) {
            case OnionPacket.DROP -> count("dropped");
            case OnionPacket.DELIVER -> {
                byte[] whole = reassembler.accept(p.payload(), now);
                if (whole != null) {
                    count("delivered");
                    for (Consumer<byte[]> s : subscribers) {
                        try {
                            s.accept(whole.clone());
                        } catch (RuntimeException e) {
                            count("subscriber-errors");
                        }
                    }
                }
            }
            case OnionPacket.FORWARD -> forward(p.next(), p.cellForNext());
            default -> count("invalid");
        }
    }

    private void forward(NodeId next, byte[] cell) {
        long delay = config.maxDelay().isZero() ? 0 : random.nextLong(config.maxDelay().toMillis() + 1);
        Runnable r = () -> {
            try {
                transport.send(next, cell);
                count("forwarded");
            } catch (RuntimeException e) {
                count("forward-failed");
            }
        };
        if (delay == 0) {
            r.run();
        } else {
            try {
                scheduler.schedule(r, delay, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                count("forward-failed");
            }
        }
    }

    private void count(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    /** Counters: sent, cover, forwarded, delivered, dropped, invalid, expired, replayed, forward-failed. */
    public Map<String, Long> stats() {
        Map<String, Long> out = new TreeMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    /** The key peers need to address this node. */
    public byte[] publicKey() {
        return identity.publicKey();
    }

    @Override
    public void close() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
        identity.destroy();
    }
}
