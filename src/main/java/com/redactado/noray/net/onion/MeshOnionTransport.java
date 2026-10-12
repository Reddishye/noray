package com.redactado.noray.net.onion;

import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Carries cells over the encrypted mesh ({@link MeshChannel#ONION}) and learns the onion keys of the peers from
 * the announcements they send. Since the mesh authenticates the sender of every message, a key announced by a peer
 * can only be that peer's own. Relays are the enrolled peers that are up and have announced a key, so a message
 * can only be relayed by (and addressed to) nodes the operator enrolled.
 *
 * <p>Wire: {@code 0x01 | cell} or {@code 0x02 | publicKey(32)}.
 */
public final class MeshOnionTransport implements OnionTransport, OnionDirectory, AutoCloseable {

    private static final byte CELL = 1;
    private static final byte ANNOUNCE = 2;

    private final MeshTransport mesh;
    private final OnionIdentity identity;
    private final Map<NodeId, byte[]> keys = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BiConsumer<NodeId, byte[]>> handlers = new CopyOnWriteArrayList<>();
    private final BiConsumer<NodeId, byte[]> listener = this::onMessage;
    private ScheduledExecutorService scheduler;

    public MeshOnionTransport(MeshTransport mesh, OnionIdentity identity) {
        this.mesh = mesh;
        this.identity = identity;
        mesh.subscribe(MeshChannel.ONION, listener);
    }

    /** Announces our key now and then, so that peers that came up later (or restarted) learn it. */
    public synchronized void startAnnouncing(long periodSeconds) {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "onion-announce");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::announce, 1, periodSeconds, TimeUnit.SECONDS);
    }

    /** Tells every peer that is up the key to use for us. */
    public void announce() {
        byte[] msg = announcement();
        for (NodeId peer : mesh.peers()) {
            try {
                mesh.send(peer, MeshChannel.ONION, msg);
            } catch (RuntimeException e) {
                // peer went away: it will hear from us at the next round
            }
        }
    }

    private byte[] announcement() {
        byte[] pub = identity.publicKey();
        byte[] msg = new byte[1 + pub.length];
        msg[0] = ANNOUNCE;
        System.arraycopy(pub, 0, msg, 1, pub.length);
        return msg;
    }

    private void onMessage(NodeId from, byte[] payload) {
        if (payload == null || payload.length < 1) {
            return;
        }
        if (payload[0] == CELL) {
            byte[] cell = Arrays.copyOfRange(payload, 1, payload.length);
            for (BiConsumer<NodeId, byte[]> h : handlers) {
                h.accept(from, cell);
            }
        } else if (payload[0] == ANNOUNCE && payload.length == 33) {
            byte[] key = Arrays.copyOfRange(payload, 1, 33);
            byte[] previous = keys.put(from, key);
            if (previous == null || !Arrays.equals(previous, key)) {
                try {
                    mesh.send(from, MeshChannel.ONION, announcement()); // so that they learn ours too
                } catch (RuntimeException e) {
                    // not reachable right now; the periodic announcement covers it
                }
            }
        }
    }

    @Override
    public void send(NodeId neighbour, byte[] cell) {
        byte[] msg = new byte[1 + cell.length];
        msg[0] = CELL;
        System.arraycopy(cell, 0, msg, 1, cell.length);
        mesh.send(neighbour, MeshChannel.ONION, msg);
    }

    @Override
    public void onCell(BiConsumer<NodeId, byte[]> handler) {
        handlers.add(handler);
    }

    @Override
    public Optional<byte[]> keyOf(NodeId node) {
        if (node.equals(mesh.localId())) {
            return Optional.of(identity.publicKey());
        }
        byte[] k = keys.get(node);
        return Optional.ofNullable(k == null ? null : k.clone());
    }

    @Override
    public Set<NodeId> relays() {
        Set<NodeId> up = mesh.peers();
        return keys.keySet().stream().filter(up::contains).collect(Collectors.toSet());
    }

    @Override
    public synchronized void close() {
        mesh.unsubscribe(MeshChannel.ONION, listener);
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
