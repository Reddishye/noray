package com.redactado.noray.net;

import com.redactado.noray.net.enroll.EnrollmentRecord;
import com.redactado.noray.net.enroll.EnrollmentStore;
import com.redactado.noray.net.overlay.StaticKeys;
import com.redactado.noray.net.overlay.UdpOverlay;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Two real overlays on loopback with a relay in between that writes down every transport packet and can drop some.
 * What an observer on the network would see, and what a lossy network would do.
 */
final class OverlayRig implements AutoCloseable {

    final NodeId idA = id(1);
    final NodeId idB = id(2);
    final UdpOverlay a;
    final UdpOverlay b;
    /** Sizes of transport packets in both directions, in order. */
    final List<Integer> seen = new CopyOnWriteArrayList<>();
    /** Every datagram the network carried, handshake included. */
    final List<byte[]> captured = new CopyOnWriteArrayList<>();
    /** Received by B: channel byte + payload. */
    final List<byte[]> receivedByB = new CopyOnWriteArrayList<>();
    final List<byte[]> receivedByA = new CopyOnWriteArrayList<>();
    /** Decides per transport packet (raw datagram) whether the network loses it. */
    volatile Predicate<byte[]> drop = d -> false;
    /** Packets the network delivers twice. */
    volatile Predicate<byte[]> duplicate = d -> false;

    private final DatagramSocket relay;
    private volatile boolean running = true;
    private volatile InetSocketAddress addrA;

    OverlayRig(Path tmp) throws Exception {
        StaticKeys ka = StaticKeys.loadOrCreate(tmp.resolve("a"), idA);
        StaticKeys kb = StaticKeys.loadOrCreate(tmp.resolve("b"), idB);
        EnrollmentStore sa = store(), sb = store();
        a = new UdpOverlay(idA, ka, sa, new byte[32], 0);
        b = new UdpOverlay(idB, kb, sb, new byte[32], 0);
        relay = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        sa.save(new EnrollmentRecord(idB, true, false, Set.of(), "127.0.0.1:" + relay.getLocalPort(),
                kb.x25519PubHex(), kb.mlkemPubHex()));
        sb.save(new EnrollmentRecord(idA, true, false, Set.of(), "", ka.x25519PubHex(), ka.mlkemPubHex()));
        a.setReceiver((from, plain) -> receivedByA.add(plain));
        b.setReceiver((from, plain) -> receivedByB.add(plain));
        Thread t = new Thread(this::pump, "test-relay");
        t.setDaemon(true);
        t.start();
    }

    private void pump() {
        byte[] buf = new byte[65535];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                relay.receive(p);
                byte[] data = Arrays.copyOf(p.getData(), p.getLength());
                captured.add(data);
                boolean transport = data.length > 1 && data[1] == UdpOverlay.TYPE_TX;
                if (transport) {
                    seen.add(data.length);
                    if (drop.test(data)) continue;
                }
                InetSocketAddress target;
                if (p.getPort() == b.localPort()) {
                    target = addrA;
                } else {
                    addrA = new InetSocketAddress(p.getAddress(), p.getPort());
                    target = new InetSocketAddress(InetAddress.getLoopbackAddress(), b.localPort());
                }
                if (target != null) {
                    relay.send(new DatagramPacket(data, data.length, target));
                    if (transport && duplicate.test(data)) relay.send(new DatagramPacket(data, data.length, target));
                }
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    static NodeId id(int n) {
        return new NodeId(String.format("%064x", n));
    }

    private static EnrollmentStore store() {
        Map<NodeId, EnrollmentRecord> map = new ConcurrentHashMap<>();
        return new EnrollmentStore() {
            @Override public void save(EnrollmentRecord r) { map.put(r.nodeId(), r); }
            @Override public void delete(NodeId n) { map.remove(n); }
            @Override public Optional<EnrollmentRecord> find(NodeId n) { return Optional.ofNullable(map.get(n)); }
            @Override public List<EnrollmentRecord> list() { return List.copyOf(map.values()); }
        };
    }

    void awaitB(int n) throws InterruptedException {
        for (int i = 0; i < 1500 && receivedByB.size() < n; i++) Thread.sleep(10);
    }

    void awaitA(int n) throws InterruptedException {
        for (int i = 0; i < 1500 && receivedByA.size() < n; i++) Thread.sleep(10);
    }

    @Override
    public void close() {
        running = false;
        relay.close();
        a.close();
        b.close();
    }
}
