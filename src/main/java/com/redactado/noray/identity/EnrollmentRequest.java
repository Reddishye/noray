package com.redactado.noray.identity;

import com.redactado.noray.net.NodeId;

import java.util.HexFormat;
import java.util.Objects;

/**
 * What a node shows to the authority to be admitted: its transport id and public keys (no secrets). The operator
 * checks the fingerprint with the node's owner out of band before endorsing it.
 */
public record EnrollmentRequest(NodeId nodeId, byte[] signKey, byte[] transportKey, byte[] kemKey) {

    private static final String PREFIX = "noray-id:1:";

    public EnrollmentRequest {
        Objects.requireNonNull(nodeId, "nodeId");
        if (signKey.length != 32 || (transportKey.length != 0 && transportKey.length != 32)) {
            throw new IllegalArgumentException("bad key size");
        }
        signKey = signKey.clone();
        transportKey = transportKey.clone();
        kemKey = kemKey == null ? new byte[0] : kemKey.clone();
    }

    public EnrollmentRequest(NodeId nodeId, byte[] signKey, byte[] transportKey) {
        this(nodeId, signKey, transportKey, new byte[0]);
    }

    public String toText() {
        HexFormat h = HexFormat.of();
        return PREFIX + nodeId.hex() + ":" + h.formatHex(signKey) + ":" + h.formatHex(transportKey)
                + (kemKey.length == 0 ? "" : ":" + h.formatHex(kemKey));
    }

    public static EnrollmentRequest parse(String text) {
        String t = text.trim();
        if (!t.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a noray enrollment request");
        }
        String[] p = t.substring(PREFIX.length()).split(":", -1);
        if (p.length != 3 && p.length != 4) {
            throw new IllegalArgumentException("malformed enrollment request");
        }
        HexFormat h = HexFormat.of();
        return new EnrollmentRequest(new NodeId(p[0]), h.parseHex(p[1]), h.parseHex(p[2]),
                p.length == 4 ? h.parseHex(p[3]) : new byte[0]);
    }

    /** Eight hex characters both sides can read aloud to confirm they mean the same keys. */
    public String fingerprint() {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            d.update(nodeId.hex().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            d.update(signKey);
            d.update(transportKey);
            d.update(kemKey);
            return HexFormat.of().formatHex(d.digest(), 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An unsigned certificate for this request; the authority endorses it. */
    public Certificate toCertificate(java.util.Set<Op> ops, java.time.Instant notBefore, java.time.Instant notAfter,
                                     long serial) {
        return Certificate.unsigned(nodeId, signKey, transportKey, kemKey, ops, notBefore, notAfter, serial);
    }
}
