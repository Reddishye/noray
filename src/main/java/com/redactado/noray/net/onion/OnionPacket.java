package com.redactado.noray.net.onion;

import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.overlay.NoiseCrypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * The wire format of a cell and the layering around it.
 *
 * <pre>
 *   cell   = ephemeral(32) | lenEnc(2) | body(1280)          always {@value #CELL} bytes
 *   layer  = AEAD( cmd(1) | expiry(4) | next(32) | nextEphemeral(32) | nextLenEnc(2) | inner )
 * </pre>
 *
 * Every hop has its own ephemeral X25519 key, chosen by the sender, so the same message looks different at every
 * hop; the length of the sealed layer is hidden under a per-hop mask and the rest of the body is random, so all
 * cells have the same size at every hop. A relay learns only the next hop. Nothing links a cell to the one it
 * forwards except what a timing observer can see (see {@link OnionConfig#maxDelay()}).
 *
 * <p>Layer keys are classical X25519, not post-quantum: the confidentiality of the <i>route</i> against someone
 * who recorded traffic and later breaks X25519 depends on the hybrid ML-KEM link encryption of the mesh below it.
 */
public final class OnionPacket {

    public static final int BODY = 1280;
    private static final int EPH = 32;
    private static final int LENFIELD = 2;
    public static final int CELL = EPH + LENFIELD + BODY;
    private static final int HDR = 1 + 4 + 32 + 32 + 2;
    private static final int TAG = 16;
    /** Bytes every layer adds around what it carries. */
    public static final int LAYER = HDR + TAG;
    public static final int MAX_RELAYS = 5;

    static final byte FORWARD = 1;
    static final byte DELIVER = 2;
    static final byte DROP = 3;

    private static final byte[] LABEL = NoiseCrypto.hash("noray-onion-v1".getBytes(StandardCharsets.UTF_8));
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

    private OnionPacket() {
    }

    /** What a node learns by peeling one layer. */
    record Peeled(byte command, long expirySeconds, byte[] ephemeral, NodeId next, byte[] cellForNext, byte[] payload) {
    }

    /** The most a message can carry through {@code relays} relays. */
    public static int capacity(int relays) {
        return BODY - (relays + 1) * LAYER;
    }

    private record LayerKeys(byte[] key, int mask) {
    }

    private static LayerKeys derive(byte[] shared, byte[] eph, byte[] nodePublic) {
        byte[][] k = NoiseCrypto.hkdf(LABEL, NoiseCrypto.concat(shared, eph, nodePublic), 2);
        return new LayerKeys(k[0], ((k[1][0] & 0xff) << 8) | (k[1][1] & 0xff));
    }

    private static byte[] ad(byte[] eph, byte[] nodePublic, int len) {
        return ByteBuffer.allocate(EPH + 32 + 2).put(eph).put(nodePublic).putShort((short) len).array();
    }

    /**
     * Wraps {@code payload} for {@code destination}, to cross {@code relays} on the way.
     *
     * @param keys   onion public key of every node on the route
     * @param expiry absolute expiry (epoch seconds) written into every layer
     */
    static byte[] build(List<NodeId> relays, NodeId destination, Function<NodeId, byte[]> keys, byte[] payload,
                        byte finalCommand, long expiry) {
        int n = relays.size() + 1;
        if (relays.size() > MAX_RELAYS) {
            throw new OnionException("at most " + MAX_RELAYS + " relays");
        }
        if (payload.length > capacity(relays.size())) {
            throw new OnionException("payload of " + payload.length + " bytes does not fit a cell with "
                    + relays.size() + " relays (max " + capacity(relays.size()) + ")");
        }
        NodeId[] route = new NodeId[n];
        for (int i = 0; i < relays.size(); i++) {
            route[i] = relays.get(i);
        }
        route[n - 1] = destination;

        byte[][] pub = new byte[n][];
        byte[][] ephPriv = new byte[n][];
        byte[][] ephPub = new byte[n][];
        LayerKeys[] lk = new LayerKeys[n];
        for (int i = 0; i < n; i++) {
            pub[i] = keys.apply(route[i]);
            if (pub[i] == null || pub[i].length != 32) {
                throw new OnionException("no onion key for " + route[i]);
            }
            ephPriv[i] = NoiseCrypto.x25519GeneratePrivate();
            ephPub[i] = NoiseCrypto.x25519Public(ephPriv[i]);
            lk[i] = derive(NoiseCrypto.dh(ephPriv[i], pub[i]), ephPub[i], pub[i]);
        }

        byte[] inner = payload;
        int nextLenEnc = 0;
        for (int i = n - 1; i >= 0; i--) {
            boolean last = i == n - 1;
            ByteBuffer pt = ByteBuffer.allocate(HDR + inner.length);
            pt.put(last ? finalCommand : FORWARD);
            pt.putInt((int) expiry);
            pt.put(last ? new byte[32] : HexFormat.of().parseHex(route[i + 1].hex()));
            pt.put(last ? new byte[32] : ephPub[i + 1]);
            pt.putShort((short) (last ? 0 : nextLenEnc));
            pt.put(inner);
            int len = HDR + inner.length + TAG;
            if (len > BODY) {
                throw new OnionException("message too large for a cell");
            }
            inner = NoiseCrypto.encrypt(lk[i].key(), 0, ad(ephPub[i], pub[i], len), pt.array());
            nextLenEnc = len ^ lk[i].mask();
        }
        ByteBuffer cell = ByteBuffer.allocate(CELL);
        cell.put(ephPub[0]);
        cell.putShort((short) nextLenEnc);
        cell.put(inner);
        fillRandom(cell);
        for (byte[] k : ephPriv) {
            Arrays.fill(k, (byte) 0);
        }
        return cell.array();
    }

    /** Peels the outer layer of {@code cell} with {@code identity}; {@code null} if it is not for us or not valid. */
    static Peeled peel(OnionIdentity identity, byte[] cell) {
        if (cell == null || cell.length != CELL) {
            return null;
        }
        try {
            byte[] eph = Arrays.copyOfRange(cell, 0, EPH);
            byte[] myPublic = identity.publicKey();
            LayerKeys lk = derive(identity.dh(eph), eph, myPublic);
            int lenEnc = ((cell[EPH] & 0xff) << 8) | (cell[EPH + 1] & 0xff);
            int len = lenEnc ^ lk.mask();
            if (len < LAYER || len > BODY) {
                return null;
            }
            byte[] sealed = Arrays.copyOfRange(cell, EPH + LENFIELD, EPH + LENFIELD + len);
            byte[] plain = NoiseCrypto.decrypt(lk.key(), 0, ad(eph, myPublic, len), sealed);
            ByteBuffer b = ByteBuffer.wrap(plain);
            byte cmd = b.get();
            long expiry = b.getInt() & 0xffffffffL;
            byte[] next = new byte[32];
            b.get(next);
            byte[] nextEph = new byte[32];
            b.get(nextEph);
            int nextLen = b.getShort() & 0xffff;
            byte[] inner = new byte[b.remaining()];
            b.get(inner);
            if (cmd == FORWARD) {
                if (inner.length < LAYER) {
                    return null;
                }
                ByteBuffer out = ByteBuffer.allocate(CELL);
                out.put(nextEph);
                out.putShort((short) nextLen);
                out.put(inner);
                fillRandom(out);
                return new Peeled(cmd, expiry, eph, new NodeId(HexFormat.of().formatHex(next)), out.array(), null);
            }
            if (cmd == DELIVER || cmd == DROP) {
                return new Peeled(cmd, expiry, eph, null, null, inner);
            }
            return null;
        } catch (RuntimeException e) {
            return null; // wrong key, tampered, low-order point, malformed: indistinguishable on purpose
        }
    }

    private static void fillRandom(ByteBuffer cell) {
        byte[] pad = new byte[cell.remaining()];
        RNG.nextBytes(pad);
        cell.put(pad);
    }
}
