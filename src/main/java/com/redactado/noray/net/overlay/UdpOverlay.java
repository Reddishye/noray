package com.redactado.noray.net.overlay;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.enroll.EnrollmentRecord;
import com.redactado.noray.net.enroll.EnrollmentStore;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Ephemeral UDP overlay. Type 1 handshake message 1, type 4 message 2, type 2 transport, type 3 keepalive.
 * Header: version(1) type(1) index(4) nonce(8) body
 *
 * <p>The header names no node. {@code index} is the receiver's own random session index (as in WireGuard): for a
 * transport packet the one the receiver chose for that session; for handshake message 1 the sender's, which the answer
 * (message 2, whose body starts with the responder's index) then echoes. The initiator's identity travels only inside
 * message 1, encrypted to the responder's static key, so an observer sees random indices and nothing that ties
 * packets to a node id.
 *
 * <p>Two optional layers: {@link #obfuscate} encrypts every datagram, header included, with a key derived from the mesh
 * pre-shared key (no fixed byte left for a filter to match), and {@link #enableRelay} lets this node forward datagrams
 * between two peers that cannot reach each other directly (the datagrams stay end-to-end encrypted).
 */
public final class UdpOverlay implements AutoCloseable {

    public static final byte VERSION = 1;
    public static final byte TYPE_HS = 1;
    public static final byte TYPE_TX = 2;
    public static final byte TYPE_KA = 3;
    public static final byte TYPE_HS2 = 4;
    static final int HEADER = 1 + 1 + 4 + 8;

    private static final HexFormat HEX = HexFormat.of();
    private static final System.Logger LOG = System.getLogger("noray.net");

    private final NodeId localId;
    private final StaticKeys keys;
    private final EnrollmentStore enroll;
    private final byte[] psk;
    private final DatagramSocket socket;
    private final Thread thread;
    private volatile boolean running = true;
    private final Map<NodeId, PeerState> peers = new ConcurrentHashMap<>();
    /** Session index (chosen by us, appears in packets addressed to us) to the peer it belongs to. */
    private final Map<Integer, PeerState> byIndex = new ConcurrentHashMap<>();
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private final HandshakeBudget budget = new HandshakeBudget(200, 100);
    private final java.util.concurrent.atomic.AtomicLong lastTimestamp = new java.util.concurrent.atomic.AtomicLong();
    /** Sessions are replaced after this long (forward secrecy: a key that is captured later opens little). */
    private volatile long sessionLifetimeMs = 120_000;
    /** ...or after this many packets, whichever comes first. */
    static final long REKEY_AFTER_PACKETS = 1L << 30;
    /** A message 1 whose clock is further than this from ours is not accepted (replays of old recordings). */
    static final long HANDSHAKE_WINDOW_MS = 300_000;
    private volatile Obfuscation[] obfuscation; // one per mesh key we accept (current first), null when off
    private volatile byte[] previousPsk;
    /** Which of our mesh keys each remote address spoke with last (index into {@link #psks()}). */
    private final Map<SocketAddress, Integer> addressKey = new ConcurrentHashMap<>();
    private volatile boolean relayEnabled;
    private volatile long relayBytesPerSecond = 2L * 1024 * 1024;
    private volatile java.util.concurrent.ScheduledExecutorService keeper;
    private volatile BiConsumer<NodeId, byte[]> onPlain; // channel ordinal + payload
    private volatile TrafficShape shape = new TrafficShape(0);
    private volatile int maxMessageBytes = 16 * 1024 * 1024;
    private volatile long sendTimeoutMillis = 30_000;
    private volatile long peerBytesPerSecond; // 0 = unlimited
    private volatile java.util.concurrent.ScheduledExecutorService cover;

    public UdpOverlay(NodeId localId, StaticKeys keys, EnrollmentStore enroll, byte[] psk, int bindPort) {
        this.localId = localId;
        this.keys = keys;
        this.enroll = enroll;
        this.psk = psk == null ? new byte[32] : psk;
        try {
            this.socket = new DatagramSocket(bindPort);
        } catch (Exception e) {
            throw new IllegalStateException("UDP bind failed", e);
        }
        this.thread = new Thread(this::loop, "noray-net-udp");
        this.thread.setDaemon(true);
        this.thread.start();
        LOG.log(System.Logger.Level.INFO, "noray.net overlay udp bound on :" + socket.getLocalPort() + " (ephemeral unless configured)");
        LOG.log(System.Logger.Level.INFO, "noray.net nodeId=" + localId.hex());
        LOG.log(System.Logger.Level.INFO, "noray.net x25519pub=" + keys.x25519PubHex());
        LOG.log(System.Logger.Level.INFO, "noray.net mlkem768pub=" + keys.mlkemPubHex());
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    /** A session that heard nothing for this long is replaced by a fresh handshake before the next send. */
    static final long QUIET_BEFORE_REKEY_MS = 15_000;
    /** We sent and nothing came back for this long: the peer may have lost the session. */
    static final long UNANSWERED_BEFORE_REKEY_MS = 1_500;
    private static final long FIRST_WAIT_MS = 3_000;
    /** A replacement handshake is an optimisation: do not stall the sender for long if the peer is simply gone. */
    private static final long REKEY_WAIT_MS = 1_500;
    private static final long MIN_REKEY_GAP_MS = 3_000;
    private static final long MIN_REHANDSHAKE_GAP_MS = 200;

    public void setReceiver(BiConsumer<NodeId, byte[]> onPlain) {
        this.onPlain = onPlain;
    }

    /** Encrypts every datagram on the wire with a key derived from the pre-shared key; all peers must agree. */
    public void obfuscate(boolean on) {
        this.obfuscation = on ? obfuscations() : null;
    }

    /**
     * Rotation of the mesh key: this node keeps talking to peers that still hold {@code previous} until they are updated
     * (handshakes carry a tag that tells the responder which key the initiator uses; datagrams are read with either).
     * Roll the new key out node by node, then drop the old one everywhere.
     */
    public void rotateFrom(byte[] previous) {
        this.previousPsk = previous == null || java.util.Arrays.equals(previous, psk) ? null : previous;
        if (obfuscation != null) this.obfuscation = obfuscations();
    }

    private byte[][] psks() {
        byte[] prev = previousPsk;
        return prev == null ? new byte[][]{psk} : new byte[][]{psk, prev};
    }

    private Obfuscation[] obfuscations() {
        byte[][] keysNow = psks();
        Obfuscation[] out = new Obfuscation[keysNow.length];
        for (int i = 0; i < out.length; i++) out[i] = new Obfuscation(keysNow[i]);
        return out;
    }

    /** Clock for message 1: wall time, but never twice the same value (the responder wants it strictly increasing). */
    private long nextTimestamp() {
        long now = System.currentTimeMillis();
        return lastTimestamp.updateAndGet(prev -> Math.max(prev + 1, now));
    }

    /** How long a session lives before the next send replaces it with a fresh handshake (default 2 minutes). */
    public void sessionLifetime(java.time.Duration d) {
        this.sessionLifetimeMs = Math.max(1_000, d.toMillis());
    }

    /** One byte that says which mesh key a handshake was made with; useless to anyone without the key. */
    private static byte pskTag(byte[] psk) {
        return NoiseCrypto.hkdf(psk, "noray/psk-tag/v1".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1)[0][0];
    }

    /** Lets this node forward datagrams between peers, each source limited to {@code bytesPerSecond}. */
    public void enableRelay(boolean on, long bytesPerSecond) {
        this.relayEnabled = on;
        this.relayBytesPerSecond = Math.max(1024, bytesPerSecond);
    }

    /**
     * Keeps sessions with these nodes (relays this node depends on to be reachable) alive: connects when there is none
     * and sends a tiny frame every {@code everySeconds} so that a NAT keeps the mapping.
     */
    public synchronized void keepConnected(java.util.Set<NodeId> relays, int everySeconds) {
        if (keeper != null) {
            keeper.shutdownNow();
            keeper = null;
        }
        if (relays.isEmpty() || !running) return;
        var ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "noray-net-keep");
            t.setDaemon(true);
            return t;
        });
        keeper = ex;
        ex.scheduleWithFixedDelay(() -> {
            for (NodeId relay : relays) {
                try {
                    PeerState st = peers.computeIfAbsent(relay, PeerState::new);
                    synchronized (st) {
                        if (st.session == null) {
                            ensureInitiator(st);
                        } else {
                            transmit(st, TrafficShape.COVER_CHANNEL, new byte[0]);
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "keep-alive to " + relay + " failed", e);
                }
            }
        }, 0, Math.max(1, everySeconds), TimeUnit.SECONDS);
    }

    /**
     * Hides packet sizes (padding to multiples of {@code padBucket} bytes) and, when {@code coverMeanMillis} is positive,
     * sends dummy packets to every connected peer at random intervals with that mean.
     */
    public synchronized void shapeTraffic(int padBucket, long coverMeanMillis) {
        this.shape = new TrafficShape(padBucket);
        if (cover != null) {
            cover.shutdownNow();
            cover = null;
        }
        if (coverMeanMillis > 0 && running) {
            var ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "noray-net-cover");
                t.setDaemon(true);
                return t;
            });
            cover = ex;
            scheduleCover(ex, coverMeanMillis);
        }
    }

    private void scheduleCover(java.util.concurrent.ScheduledExecutorService ex, long mean) {
        // exponential gaps: the cover itself shows no rhythm
        long gap = Math.max(1, (long) (-Math.log(1 - java.util.concurrent.ThreadLocalRandom.current().nextDouble()) * mean));
        try {
            ex.schedule(() -> {
                try {
                    sendCover();
                } catch (RuntimeException ignored) {
                    // a peer that went away is not our problem here
                }
                if (running && !ex.isShutdown()) {
                    scheduleCover(ex, mean);
                }
            }, gap, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shut down
        }
    }

    private void sendCover() {
        for (PeerState st : peers.values()) {
            synchronized (st) {
                if (st.session == null || st.remote == null) continue;
                TrafficShape sh = shape;
                // at least one bucket, sometimes more, like the traffic it imitates
                int extra = java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
                byte[] inner = sh.pad(TrafficShape.COVER_CHANNEL, new byte[0], Math.max(1, sh.bucket()) * (1 + extra));
                long nonce = st.session.peekSendNonce();
                byte[] ad = ad(TYPE_TX, st.remoteIndex);
                sendRaw(st.remote, pack(TYPE_TX, st.remoteIndex, nonce, st.session.encrypt(ad, inner)));
            }
        }
    }

    /**
     * Caps what this node sends to each single peer, so that one big transfer cannot take the whole uplink (and so that
     * a repair storm toward one node is spread out). 0 turns the cap off.
     */
    public void peerBandwidth(long bytesPerSecond) {
        this.peerBytesPerSecond = Math.max(0, bytesPerSecond);
    }

    /** Blocks (outside any lock the receive loop needs) until {@code bytes} fit in the peer's allowance. */
    private void pace(PeerState st, int bytes) {
        long rate = peerBytesPerSecond;
        if (rate <= 0) return;
        long waitNanos;
        synchronized (st.paceLock) {
            long now = System.nanoTime();
            double burst = Math.max(32 * 1024, rate / 4.0);
            if (st.paceLast == 0) {
                st.paceTokens = burst;
            } else {
                st.paceTokens = Math.min(burst, st.paceTokens + (now - st.paceLast) / 1e9 * rate);
            }
            st.paceLast = now;
            st.paceTokens -= bytes + 40;
            waitNanos = st.paceTokens >= 0 ? 0 : (long) (-st.paceTokens / rate * 1e9);
        }
        if (waitNanos > 0) {
            com.redactado.noray.kernel.metrics.Metrics.global().inc("net.throttled");
            java.util.concurrent.locks.LockSupport.parkNanos(waitNanos);
        }
    }

    /** Largest message accepted or sent (bigger ones are refused), and how long a sender waits for acknowledgements. */
    public void limits(int maxMessageBytes, long sendTimeoutMillis) {
        this.maxMessageBytes = Math.max(Fragments.FRAGMENT_DATA, maxMessageBytes);
        this.sendTimeoutMillis = Math.max(100, sendTimeoutMillis);
    }

    /**
     * Sends one message. Up to {@value Fragments#FRAGMENT_DATA} bytes it is a single datagram (no acknowledgement, like
     * before); larger messages are fragmented, acknowledged selectively and retransmitted, and this call returns only
     * once the peer has all of it.
     *
     * @throws FsException VOLUME_UNAVAILABLE when the peer does not acknowledge within the send timeout
     */
    public void sendPlain(NodeId peer, MeshChannel channel, byte[] payload) {
        PeerState st = peers.computeIfAbsent(peer, p -> new PeerState(p));
        final long started = System.currentTimeMillis();
        synchronized (st) {
            ensureInitiator(st);
            long heard = Math.max(st.lastReceived, st.establishedAt);
            boolean idleTooLong = started - heard > QUIET_BEFORE_REKEY_MS;
            boolean unanswered = st.lastSent > heard && started - heard > UNANSWERED_BEFORE_REKEY_MS;
            boolean worn = st.session != null && (started - st.establishedAt > sessionLifetimeMs
                    || st.session.peekSendNonce() > REKEY_AFTER_PACKETS);
            if (st.session != null && (idleTooLong || unanswered || worn) && started - st.lastRekey > MIN_REKEY_GAP_MS) {
                st.lastRekey = started;
                // Nothing has come back for a while: the peer may have restarted and lost the session. A fresh
                // handshake is cheap compared to sending into the void for the whole timeout.
                try {
                    handshake(st, REKEY_WAIT_MS);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "rehandshake with " + peer + " failed, keeping the session", e);
                }
            }
            if (st.session == null) {
                throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "overlay handshake incomplete: " + peer);
            }
            if (payload.length <= Fragments.FRAGMENT_DATA) {
                // (waits outside the lock below when a cap is set)
                if (peerBytesPerSecond <= 0) {
                    transmit(st, channel.wireId(), payload);
                    return;
                }
            }
        }
        if (payload.length <= Fragments.FRAGMENT_DATA) {
            pace(st, payload.length);
            transmit(st, channel.wireId(), payload);
            return;
        }
        if (payload.length > maxMessageBytes) {
            throw new FsException(FsErrorCode.INVALID_PATH, "message of " + payload.length + " bytes exceeds the overlay limit of "
                    + maxMessageBytes);
        }
        Fragments.Outgoing out = new Fragments.Outgoing(st.nextMessage.incrementAndGet(), channel.wireId(), payload);
        st.outgoing.put(out.msgId, out);
        try {
            out.run((type, body) -> {
                pace(st, body.length);
                transmit(st, type, body);
            }, sendTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FsException(FsErrorCode.INTERNAL, "send interrupted");
        } catch (FsException e) {
            if (st.lastReceived < started) {
                st.session = null; // not a single packet came back: start over with the next send
            }
            throw e;
        } finally {
            st.outgoing.remove(out.msgId);
        }
    }

    /** Pads, encrypts and sends one frame; the nonce and the encryption must not interleave with other senders. */
    private void transmit(PeerState st, int type, byte[] body) {
        synchronized (st) {
            if (st.session == null) {
                throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "no session with " + st.peer);
            }
            byte[] inner = shape.pad(type, body);
            st.lastSent = System.currentTimeMillis();
            long nonce = st.session.peekSendNonce();
            byte[] ct = st.session.encrypt(ad(TYPE_TX, st.remoteIndex), inner);
            sendRaw(st.remote, pack(TYPE_TX, st.remoteIndex, nonce, ct));
        }
    }

    private void onFragment(PeerState st, NodeId from, byte[] frame) {
        Fragments.Frag f = Fragments.parseFrag(frame);
        if ((long) f.count() * Fragments.FRAGMENT_DATA > (long) maxMessageBytes + Fragments.FRAGMENT_DATA) {
            return; // larger than we accept
        }
        if (st.completed.contains(f.msgId())) { // it did not see our acknowledgement: say it again
            transmit(st, Fragments.TYPE_ACK, Fragments.encodeAck(f.msgId(), f.count(), new byte[Fragments.BITMAP_BYTES]));
            return;
        }
        long now = System.nanoTime();
        st.incoming.values().removeIf(i -> now - i.created > 120_000_000_000L); // abandoned
        Fragments.Incoming in = st.incoming.get(f.msgId());
        if (in == null) {
            if (st.incoming.size() >= 8) {
                return;
            }
            in = new Fragments.Incoming(f.count(), f.channel());
            st.incoming.put(f.msgId(), in);
        } else if (!in.compatible(f)) {
            return;
        }
        boolean fresh = in.accept(f);
        int base = in.base();
        boolean done = in.complete();
        // acknowledge often enough to keep the sender going, and immediately on anything unusual
        if (done || !fresh || f.index() > base || f.index() == f.count() - 1 || in.received() % 8 == 0) {
            transmit(st, Fragments.TYPE_ACK, Fragments.encodeAck(f.msgId(), base, in.bitmapFrom(base)));
        }
        if (done) {
            st.incoming.remove(f.msgId());
            st.completed.add(f.msgId());
            while (st.completed.size() > 256) {
                st.completed.remove(st.completed.iterator().next());
            }
            byte[] payload = in.assemble();
            deliver(from, NoiseCrypto.concat(new byte[]{(byte) in.channel}, payload));
        }
    }

    /** Handlers may themselves send large replies (and wait for acknowledgements), so they never run on the receive thread. */
    private void deliver(NodeId from, byte[] channelAndPayload) {
        BiConsumer<NodeId, byte[]> r = onPlain;
        if (r == null) return;
        Thread.startVirtualThread(() -> {
            try {
                r.accept(from, channelAndPayload);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "message handler failed", e);
            }
        });
    }

    public void ensureInitiator(NodeId peer, InetSocketAddress addr) {
        PeerState st = peers.computeIfAbsent(peer, p -> new PeerState(p));
        synchronized (st) {
            if (addr != null) st.remote = addr;
            ensureInitiator(st);
        }
    }

    private void ensureInitiator(PeerState st) {
        if (st.session != null) return;
        handshake(st, FIRST_WAIT_MS);
    }

    /** Runs a handshake as the initiator (also to replace a session whose peer may have lost its state). */
    private void handshake(PeerState st, long waitMillis) {
        EnrollmentRecord rec = enroll.find(st.peer).orElseThrow(
                () -> new FsException(FsErrorCode.DENIED, "not enrolled: " + st.peer));
        if (rec.x25519Pub().isEmpty() || rec.mlkemPub().isEmpty()) {
            throw new FsException(FsErrorCode.DENIED, "peer static keys missing in enrollment: " + st.peer);
        }
        byte[] rs = HEX.parseHex(rec.x25519Pub());
        MLKEMPublicKeyParameters rkem = new MLKEMPublicKeyParameters(
                MLKEMParameters.ml_kem_768, HEX.parseHex(rec.mlkemPub()));
        FsException last = null;
        int keyCount = psks().length;
        for (int k = 0; k < keyCount; k++) {
            int idx = (st.pskIdx + k) % keyCount;
            if (k > 0) { // the peer rations handshakes per source: let its window pass before the other key
                try {
                    Thread.sleep(MIN_REHANDSHAKE_GAP_MS + 100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FsException(FsErrorCode.INTERNAL, "handshake interrupted");
                }
            }
            for (SocketAddress candidate : candidates(st, rec)) {
                try {
                    handshakeVia(st, candidate, rs, rkem, waitMillis, idx);
                    st.pskIdx = idx;
                    return;
                } catch (FsException e) {
                    last = e;
                }
            }
        }
        throw last != null ? last : new FsException(FsErrorCode.DENIED, "no address known for " + st.peer);
    }

    /**
     * Where to try, in order: the address that worked last, then the enrolled hints, a comma-separated list of
     * {@code host:port} and {@code relay:<node id>} (reach the peer through that relay node).
     */
    private List<SocketAddress> candidates(PeerState st, EnrollmentRecord rec) {
        List<SocketAddress> out = new java.util.ArrayList<>();
        if (st.remote != null) out.add(st.remote);
        String hint = rec.introHint();
        if (hint != null) {
            for (String part : hint.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                SocketAddress a;
                if (part.startsWith("relay:")) {
                    a = new RelayAddress(new NodeId(part.substring(6).trim()), st.peer);
                } else {
                    a = parseHint(part);
                }
                if (!out.contains(a)) out.add(a);
            }
        }
        return out;
    }

    private void handshakeVia(PeerState st, SocketAddress target, byte[] rs, MLKEMPublicKeyParameters rkem, long waitMillis,
                              int pskIdx) {
        byte[] usedPsk = psks()[Math.min(pskIdx, psks().length - 1)];
        addressKey.put(target, pskIdx);
        if (addressKey.size() > 4096) addressKey.clear();
        st.hs = new NoiseHandshake(true, keys.x25519Priv, keys.x25519Pub, rs, usedPsk, rkem, keys.mlkemPriv);
        int mine = newIndex(st);
        byte[] msg1 = st.hs.writeMessage1(pskTag(usedPsk), nextTimestamp());
        st.msg2.clear();
        st.handshakeIndex = mine;
        st.awaiting = true; // before sending: the answer can arrive before this method returns
        boolean done = false;
        try {
            sendRaw(target, pack(TYPE_HS, mine, 0, msg1));
            waitMsg2(st, waitMillis, mine);
            st.remote = target;
            done = true;
        } finally {
            st.awaiting = false;
            if (!done) byIndex.remove(mine);
        }
    }

    private void waitMsg2(PeerState st, long waitMillis, int mine) {
        try {
            byte[] body = st.msg2.poll(waitMillis, TimeUnit.MILLISECONDS);
            if (body == null) {
                throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "handshake timeout: " + st.peer);
            }
            if (body.length < 4) {
                throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "handshake answer too short: " + st.peer);
            }
            int theirs = ByteBuffer.wrap(body, 0, 4).getInt();
            try {
                st.hs.readMessage2(java.util.Arrays.copyOfRange(body, 4, body.length));
            } catch (RuntimeException e) {
                throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "handshake answer rejected: " + st.peer, e);
            }
            NoiseSession fresh = st.hs.split();
            st.hs = null;
            install(st, fresh, mine, theirs, null);
            com.redactado.noray.kernel.metrics.Metrics.global().inc("net.handshakes.out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FsException(FsErrorCode.INTERNAL, "handshake interrupted");
        }
    }

    /** Makes {@code fresh} the current session; the one it replaces stays readable until the next replacement. */
    private void install(PeerState st, NoiseSession fresh, int local, int remote, SocketAddress addr) {
        if (st.prev != null) byIndex.remove(st.prevLocal);
        st.prev = st.session;
        st.prevLocal = st.localIndex;
        if (st.prev == null) st.prevLocal = 0;
        st.session = fresh;
        st.localIndex = local;
        st.remoteIndex = remote;
        if (addr != null) st.remote = addr;
        st.establishedAt = System.currentTimeMillis();
    }

    /** A fresh random index, registered so that packets carrying it reach {@code st}. */
    private int newIndex(PeerState st) {
        while (true) {
            int i = random.nextInt();
            if (i != 0 && byIndex.putIfAbsent(i, st) == null) return i;
        }
    }

    private void loop() {
        byte[] buf = new byte[65535];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                byte[] data = ArraysCopy(pkt.getData(), pkt.getOffset(), pkt.getLength());
                var metrics = com.redactado.noray.kernel.metrics.Metrics.global();
                metrics.inc("net.packets.in");
                metrics.add("net.bytes.in", data.length);
                SocketAddress from = new InetSocketAddress(pkt.getAddress(), pkt.getPort());
                Obfuscation[] ob = obfuscation;
                if (ob != null) {
                    byte[] plain = null;
                    for (int i = 0; i < ob.length && plain == null; i++) {
                        byte[] t = ob[i].unwrap(data);
                        // with two keys, only a datagram that reads as ours under that key counts
                        if (t != null && (ob.length == 1 || (t.length >= HEADER && t[0] == VERSION && t[1] >= TYPE_HS && t[1] <= TYPE_HS2))) {
                            plain = t;
                            if (ob.length > 1) {
                                addressKey.put(from, i);
                                if (addressKey.size() > 4096) addressKey.clear();
                            }
                        }
                    }
                    if (plain == null) continue;
                    data = plain;
                }
                handle(data, from);
            } catch (IOException e) {
                if (running) {
                    LOG.log(System.Logger.Level.DEBUG, "udp receive", e);
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "udp drop", e);
            }
        }
    }

    private void handle(byte[] data, SocketAddress addr) {
        if (data.length < HEADER) return;
        if (data[0] != VERSION) return;
        byte type = data[1];
        int index = ByteBuffer.wrap(data, 2, 4).getInt();
        long nonce = ByteBuffer.wrap(data, 6, 8).getLong();
        byte[] body = java.util.Arrays.copyOfRange(data, HEADER, data.length);
        if (type == TYPE_HS) {
            // identifying the sender costs two DH and a decapsulation: off the receive loop, and rationed
            if (!budget.tryTake()) {
                com.redactado.noray.kernel.metrics.Metrics.global().inc("net.handshakes.rationed");
                return;
            }
            Thread.startVirtualThread(() -> {
                try {
                    onHandshake(index, body, addr);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "handshake dropped", e);
                }
            });
            return;
        }
        PeerState st = byIndex.get(index);
        if (st == null) return;
        if (type == TYPE_HS2) {
            if (st.awaiting && st.handshakeIndex == index) st.msg2.offer(body);
            return;
        }
        if (type != TYPE_TX) return;
        NodeId from = st.peer;
        byte[] ad = ad(TYPE_TX, index);
        byte[] plain = TrafficShape.unpad(decryptFrom(st, index, nonce, ad, body, addr));
        st.lastReceived = System.currentTimeMillis();
        switch (plain[0] & 0xFF) {
            case TrafficShape.COVER_CHANNEL -> { }
            case Fragments.TYPE_FRAG -> onFragment(st, from, plain);
            case Fragments.TYPE_ACK -> {
                Fragments.Ack a = Fragments.parseAck(plain);
                Fragments.Outgoing o = st.outgoing.get(a.msgId());
                if (o != null) o.onAck(a);
            }
            case Fragments.TYPE_RELAY -> onRelayRequest(st, from, plain);
            case Fragments.TYPE_RELAYED -> onRelayed(from, plain);
            default -> deliver(from, plain);
        }
    }

    /**
     * Decrypts with the session the index belongs to. A packet that only the session of a newer handshake can open
     * proves that the peer holds it, and only then does that session replace the old one (so a replayed handshake
     * changes nothing).
     */
    private byte[] decryptFrom(PeerState st, int index, long nonce, byte[] ad, byte[] body, SocketAddress addr) {
        if (index == st.localIndex && st.session != null) {
            return st.session.decrypt(nonce, ad, body);
        }
        NoiseSession old = st.prev;
        if (index == st.prevLocal && old != null) {
            return old.decrypt(nonce, ad, body);
        }
        NoiseSession next = st.pending;
        if (next == null || index != st.pendingLocal) {
            throw new IllegalStateException("no session for index");
        }
        byte[] plain = next.decrypt(nonce, ad, body);
        synchronized (st) {
            if (st.pending == next) {
                st.pending = null;
                install(st, next, st.pendingLocal, st.pendingRemote, addr);
            }
        }
        return plain;
    }

    /** The static key a handshake revealed, mapped to the enrolled node that owns it. */
    private EnrollmentRecord enrolledByStatic(byte[] x25519) {
        String hex = HEX.formatHex(x25519);
        for (EnrollmentRecord r : enroll.list()) {
            if (hex.equalsIgnoreCase(r.x25519Pub())) return r;
        }
        return null;
    }

    private void onHandshake(int theirIndex, byte[] body, SocketAddress addr) {
        NoiseHandshake hs = new NoiseHandshake(false, keys.x25519Priv, keys.x25519Pub, null, psk, null, keys.mlkemPriv);
        byte[] remoteStatic = hs.readMessage1(body);
        byte[][] known = psks();
        int chosenKey = 0;
        if (known.length > 1) { // the initiator says (encrypted) which mesh key it holds
            int tag = hs.peerTag();
            for (int i = 0; i < known.length; i++) {
                if ((pskTag(known[i]) & 0xff) == tag) {
                    hs.usePsk(known[i]);
                    chosenKey = i;
                    break;
                }
            }
        }
        EnrollmentRecord rec = enrolledByStatic(remoteStatic);
        if (rec == null) { // not one of ours: no answer at all
            com.redactado.noray.kernel.metrics.Metrics.global().inc("net.handshakes.refused");
            return;
        }
        com.redactado.noray.kernel.metrics.Metrics.global().inc("net.handshakes.in");
        PeerState st = peers.computeIfAbsent(rec.nodeId(), PeerState::new);
        synchronized (st) {
            long now = System.currentTimeMillis();
            if (st.session != null && now - st.lastHandshake < MIN_REHANDSHAKE_GAP_MS) return;
            long stamp = hs.peerTimestamp();
            if (Math.abs(now - stamp) > HANDSHAKE_WINDOW_MS || stamp <= st.lastInitiatorStamp) {
                com.redactado.noray.kernel.metrics.Metrics.global().inc("net.handshakes.replayed");
                return; // a recording of an old message 1 (or a clock that is far off): no answer, no state change
            }
            st.lastInitiatorStamp = stamp;
            st.lastHandshake = now;
            st.pskIdx = chosenKey;
            byte[] msg2 = hs.writeMessage2();
            NoiseSession fresh = hs.split();
            int mine = newIndex(st);
            if (st.session == null) {
                install(st, fresh, mine, theirIndex, addr);
            } else {
                if (st.pending != null) byIndex.remove(st.pendingLocal);
                st.pending = fresh; // takes over once the peer proves it holds it
                st.pendingLocal = mine;
                st.pendingRemote = theirIndex;
            }
            byte[] answer = ByteBuffer.allocate(4 + msg2.length).putInt(mine).put(msg2).array();
            sendRaw(addr, pack(TYPE_HS2, theirIndex, 0, answer));
        }
    }

    private void sendRaw(SocketAddress to, byte[] data) {
        if (to == null) {
            throw new FsException(FsErrorCode.DENIED, "no remote address");
        }
        if (to instanceof RelayAddress via) {
            sendViaRelay(via, data);
            return;
        }
        Obfuscation[] ob = obfuscation;
        byte[] wire = data;
        if (ob != null) {
            Integer which = addressKey.get(to);
            wire = ob[which == null || which >= ob.length ? 0 : which].wrap(data);
        }
        var metrics = com.redactado.noray.kernel.metrics.Metrics.global();
        metrics.inc("net.packets.out");
        metrics.add("net.bytes.out", wire.length);
        try {
            socket.send(new DatagramPacket(wire, wire.length, to));
        } catch (IOException e) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "udp send failed", e);
        }
    }

    // ── relaying ───────────────────────────────────────────────────────────────

    /** Where a peer is reached through another node: our datagram goes to {@code via}, which hands it to {@code target}. */
    static final class RelayAddress extends SocketAddress {
        private final NodeId via;
        private final NodeId target;

        RelayAddress(NodeId via, NodeId target) {
            this.via = via;
            this.target = target;
        }

        NodeId via() { return via; }
        NodeId target() { return target; }

        @Override public boolean equals(Object o) {
            return o instanceof RelayAddress r && r.via.equals(via) && r.target.equals(target);
        }

        @Override public int hashCode() { return Objects.hash(via, target); }

        @Override public String toString() { return "relay:" + via.hex() + "->" + target.hex(); }
    }

    private void sendViaRelay(RelayAddress to, byte[] datagram) {
        PeerState relay = peers.computeIfAbsent(to.via(), PeerState::new);
        synchronized (relay) {
            ensureInitiator(relay);
            transmit(relay, Fragments.TYPE_RELAY, concat(HEX.parseHex(to.target().hex()), datagram));
        }
    }

    /** We are the relay: {@code from} asks us to pass a datagram to {@code target}. */
    private void onRelayRequest(PeerState fromState, NodeId from, byte[] frame) {
        if (!relayEnabled || frame.length < 1 + 32 + HEADER) return;
        NodeId target = new NodeId(HEX.formatHex(java.util.Arrays.copyOfRange(frame, 1, 33)));
        if (target.equals(from) || target.equals(localId)) return;
        PeerState dst = peers.get(target);
        if (dst == null || dst.session == null || dst.remote == null) return; // unknown or not connected to us
        if (!relayAllowance(fromState, frame.length)) {
            com.redactado.noray.kernel.metrics.Metrics.global().inc("net.relay.limited");
            return;
        }
        com.redactado.noray.kernel.metrics.Metrics.global().add("net.relay.bytes", frame.length);
        byte[] datagram = java.util.Arrays.copyOfRange(frame, 33, frame.length);
        try {
            synchronized (dst) {
                transmit(dst, Fragments.TYPE_RELAYED, concat(HEX.parseHex(from.hex()), datagram));
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "relay to " + target + " failed", e);
        }
    }

    /** Token bucket per source so one peer cannot use a relay's whole uplink. */
    private boolean relayAllowance(PeerState src, int bytes) {
        synchronized (src) {
            long now = System.nanoTime();
            double cap = relayBytesPerSecond * 2.0;
            if (src.relayTokens < 0) src.relayTokens = cap;
            src.relayTokens = Math.min(cap, src.relayTokens + (now - src.relayLast) / 1e9 * relayBytesPerSecond);
            src.relayLast = now;
            if (src.relayTokens < bytes) return false;
            src.relayTokens -= bytes;
            return true;
        }
    }

    /** A relay delivers a datagram that {@code source} sent to us; it is end-to-end protected, we treat it like any other. */
    private void onRelayed(NodeId relay, byte[] frame) {
        if (frame.length < 1 + 32 + HEADER) return;
        NodeId source = new NodeId(HEX.formatHex(java.util.Arrays.copyOfRange(frame, 1, 33)));
        if (source.equals(localId) || source.equals(relay)) return;
        handle(java.util.Arrays.copyOfRange(frame, 33, frame.length), new RelayAddress(relay, source));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] pack(byte type, int index, long nonce, byte[] body) {
        ByteBuffer bb = ByteBuffer.allocate(HEADER + body.length);
        bb.put(VERSION);
        bb.put(type);
        bb.putInt(index);
        bb.putLong(nonce);
        bb.put(body);
        return bb.array();
    }

    private static byte[] ad(byte type, int sessionId) {
        ByteBuffer bb = ByteBuffer.allocate(1 + 1 + 4);
        bb.put(VERSION);
        bb.put(type);
        bb.putInt(sessionId);
        return bb.array();
    }

    static InetSocketAddress parseHint(String hint) {
        if (hint == null || hint.isBlank()) {
            throw new FsException(FsErrorCode.DENIED, "empty introHint");
        }
        hint = hint.trim();
        if (hint.startsWith("[")) {
            int end = hint.indexOf(']');
            String host = hint.substring(1, end);
            int port = Integer.parseInt(hint.substring(end + 2));
            return new InetSocketAddress(host, port);
        }
        int c = hint.lastIndexOf(':');
        return new InetSocketAddress(hint.substring(0, c), Integer.parseInt(hint.substring(c + 1)));
    }

    private static byte[] ArraysCopy(byte[] src, int off, int len) {
        return java.util.Arrays.copyOfRange(src, off, off + len);
    }

    @Override
    public void close() {
        running = false;
        var c = cover;
        if (c != null) c.shutdownNow();
        var k = keeper;
        if (k != null) k.shutdownNow();
        socket.close();
        thread.interrupt();
    }

    /** Token bucket: at most {@code perSecond} handshakes a second, bursts up to {@code burst}. */
    static final class HandshakeBudget {
        private final double perSecond;
        private final double burst;
        private double tokens;
        private long last = System.nanoTime();

        HandshakeBudget(double perSecond, double burst) {
            this.perSecond = perSecond;
            this.burst = burst;
            this.tokens = burst;
        }

        synchronized boolean tryTake() {
            long now = System.nanoTime();
            tokens = Math.min(burst, tokens + (now - last) / 1e9 * perSecond);
            last = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }
    }

    static final class PeerState {
        final NodeId peer;
        final LinkedBlockingQueue<byte[]> msg2 = new LinkedBlockingQueue<>(1);
        volatile SocketAddress remote;
        double relayTokens = -1;
        long relayLast;
        volatile long lastInitiatorStamp;
        final Object paceLock = new Object();
        double paceTokens;
        long paceLast;
        volatile NoiseHandshake hs;
        volatile int pskIdx; // which of the mesh keys worked with this peer last
        volatile NoiseSession session;
        volatile int localIndex;   // what the peer puts in packets for the current session
        volatile int remoteIndex;  // what we put in packets for it
        volatile NoiseSession prev;
        volatile int prevLocal;
        volatile int handshakeIndex;
        volatile boolean awaiting;
        volatile NoiseSession pending;
        volatile int pendingLocal;
        volatile int pendingRemote;
        volatile long lastReceived;
        volatile long lastSent;
        volatile long lastRekey;
        volatile long establishedAt;
        volatile long lastHandshake;
        final java.util.concurrent.atomic.AtomicInteger nextMessage = new java.util.concurrent.atomic.AtomicInteger();
        final Map<Integer, Fragments.Outgoing> outgoing = new ConcurrentHashMap<>();
        final Map<Integer, Fragments.Incoming> incoming = new ConcurrentHashMap<>();
        final java.util.Set<Integer> completed = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

        PeerState(NodeId peer) {
            this.peer = peer;
        }
    }
}
