package com.redactado.noray.identity;

import com.redactado.noray.net.NodeId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Statement by the cluster authority: "the node with this id, signing key and transport key may perform these
 * operations between these two instants". The authority is a set of keys with a threshold, so one stolen
 * authority key cannot enrol, promote or revive a node. A certificate carries no secret and can be shown to anyone.
 *
 * <p>Short validity is the main containment tool: a node that is compromised and never noticed loses its
 * access when its certificate expires and is not renewed, without anyone having to revoke it.
 */
public final class Certificate {

    static final String DOMAIN = "noray/certificate/v1";
    private static final int MAGIC = 0x4E525943; // "NRYC"
    private static final String TEXT_PREFIX = "noray-cert:1:";
    private static final int MAX_KEM_KEY = 2048;

    private final NodeId nodeId;
    private final byte[] signKey;
    private final byte[] transportKey;
    private final byte[] kemKey;
    private final int ops;
    private final long notBefore;
    private final long notAfter;
    private final long serial;
    private final List<Endorsement> endorsements;

    private Certificate(NodeId nodeId, byte[] signKey, byte[] transportKey, byte[] kemKey, int ops, long notBefore,
                        long notAfter, long serial, List<Endorsement> endorsements) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (signKey.length != Ed25519.KEY_SIZE) {
            throw new IllegalArgumentException("signKey must be 32 bytes");
        }
        if (transportKey.length != 0 && transportKey.length != 32) {
            throw new IllegalArgumentException("transportKey must be empty or 32 bytes");
        }
        if (kemKey.length > MAX_KEM_KEY) {
            throw new IllegalArgumentException("kemKey too large");
        }
        if (notAfter <= notBefore) {
            throw new IllegalArgumentException("certificate is never valid");
        }
        Op.fromMask(ops); // rejects unknown bits
        this.signKey = signKey.clone();
        this.transportKey = transportKey.clone();
        this.kemKey = kemKey.clone();
        this.ops = ops;
        this.notBefore = notBefore;
        this.notAfter = notAfter;
        this.serial = serial;
        this.endorsements = List.copyOf(endorsements);
    }

    public static Certificate unsigned(NodeId nodeId, byte[] signKey, byte[] transportKey, Set<Op> ops,
                                       Instant notBefore, Instant notAfter, long serial) {
        return unsigned(nodeId, signKey, transportKey, null, ops, notBefore, notAfter, serial);
    }

    /** @param kemKey the node's ML-KEM public key (post-quantum part of the transport handshake), may be empty */
    public static Certificate unsigned(NodeId nodeId, byte[] signKey, byte[] transportKey, byte[] kemKey,
                                       Set<Op> ops, Instant notBefore, Instant notAfter, long serial) {
        return new Certificate(nodeId, signKey, transportKey == null ? new byte[0] : transportKey,
                kemKey == null ? new byte[0] : kemKey, Op.mask(ops), notBefore.toEpochMilli(), notAfter.toEpochMilli(),
                serial, List.of());
    }

    public NodeId nodeId() { return nodeId; }
    public byte[] signKey() { return signKey.clone(); }
    public byte[] transportKey() { return transportKey.clone(); }
    public byte[] kemKey() { return kemKey.clone(); }
    public Set<Op> ops() { return Op.fromMask(ops); }
    public Instant notBefore() { return Instant.ofEpochMilli(notBefore); }
    public Instant notAfter() { return Instant.ofEpochMilli(notAfter); }
    /** Increases with every issuance for a node; revocation lists name the lowest serial that is still acceptable. */
    public long serial() { return serial; }
    public List<Endorsement> endorsements() { return endorsements; }

    public boolean allows(Op op) {
        return (ops & (1 << op.ordinal())) != 0;
    }

    public boolean validAt(Instant now, Duration skew) {
        long t = now.toEpochMilli();
        long s = skew.toMillis();
        return t + s >= notBefore && t - s <= notAfter;
    }

    /** The signed bytes: everything except the endorsements. */
    public byte[] body() {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            out.writeInt(MAGIC);
            out.writeByte(1);
            out.write(HexFormat.of().parseHex(nodeId.hex()));
            out.write(signKey);
            out.writeByte(transportKey.length);
            out.write(transportKey);
            out.writeShort(kemKey.length);
            out.write(kemKey);
            out.writeInt(ops);
            out.writeLong(notBefore);
            out.writeLong(notAfter);
            out.writeLong(serial);
            return buf.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Short identifier of the signed content, for comparing out of band. */
    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body()), 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns a copy that also carries {@code authority}'s endorsement. */
    public Certificate endorse(SigningKey authority) {
        Endorsement e = new Endorsement(authority.publicKey(), authority.sign(DOMAIN, body()));
        return new Certificate(nodeId, signKey, transportKey, kemKey, ops, notBefore, notAfter, serial,
                Endorsement.add(endorsements, e));
    }

    /** Combines the endorsements of two copies of the same certificate (offline threshold signing). */
    public Certificate merge(Certificate other) {
        if (!Arrays.equals(body(), other.body())) {
            throw new IllegalArgumentException("these are different certificates");
        }
        List<Endorsement> all = endorsements;
        for (Endorsement e : other.endorsements) {
            all = Endorsement.add(all, e);
        }
        return new Certificate(nodeId, signKey, transportKey, kemKey, ops, notBefore, notAfter, serial, all);
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            out.write(body());
            Endorsement.write(out, endorsements);
            return buf.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Certificate decode(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != 1) {
                throw new IllegalArgumentException("not a certificate");
            }
            byte[] id = in.readNBytes(32);
            byte[] sign = in.readNBytes(32);
            int tl = in.readUnsignedByte();
            byte[] transport = in.readNBytes(tl);
            int kl = in.readUnsignedShort();
            byte[] kem = in.readNBytes(kl);
            int ops = in.readInt();
            long nb = in.readLong();
            long na = in.readLong();
            long serial = in.readLong();
            List<Endorsement> endorsements = Endorsement.read(in);
            if (id.length != 32 || sign.length != 32 || transport.length != tl || kem.length != kl || in.available() != 0) {
                throw new IllegalArgumentException("malformed certificate");
            }
            return new Certificate(new NodeId(HexFormat.of().formatHex(id)), sign, transport, kem, ops, nb, na, serial,
                    endorsements);
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated certificate", e);
        }
    }

    public String toText() {
        return TEXT_PREFIX + HexFormat.of().formatHex(encode());
    }

    public static Certificate parse(String text) {
        String t = text.trim();
        if (!t.startsWith(TEXT_PREFIX)) {
            throw new IllegalArgumentException("not a noray certificate");
        }
        return decode(HexFormat.of().parseHex(t.substring(TEXT_PREFIX.length())));
    }

    @Override
    public String toString() {
        return "Certificate[" + nodeId.hex().substring(0, 12) + "… " + ops() + " until " + notAfter() + "]";
    }
}
