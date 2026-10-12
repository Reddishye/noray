package com.redactado.noray.identity;

import com.redactado.noray.net.NodeId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Authority-signed statement "certificates of this node with a serial below N are void". Lists are cumulative
 * (a newer list must keep every earlier entry), carry an increasing version and are endorsed by the same
 * threshold as certificates, so neither a compromised node nor a lone stolen key can un-revoke anything.
 */
public final class RevocationList {

    static final String DOMAIN = "noray/revocations/v1";
    private static final int MAGIC = 0x4E525256; // "NRRV"
    private static final int MAX_ENTRIES = 100_000;
    private static final String TEXT_PREFIX = "noray-revoke:1:";

    private final long version;
    private final long issuedAt;
    private final Map<String, Long> minSerials;
    private final List<Endorsement> endorsements;

    private RevocationList(long version, long issuedAt, Map<String, Long> minSerials, List<Endorsement> endorsements) {
        if (minSerials.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("revocation list too large");
        }
        this.version = version;
        this.issuedAt = issuedAt;
        this.minSerials = new TreeMap<>(minSerials);
        this.endorsements = List.copyOf(endorsements);
    }

    public static RevocationList empty() {
        return new RevocationList(0, 0, Map.of(), List.of());
    }

    public static RevocationList unsigned(long version, long issuedAtMillis, Map<NodeId, Long> minSerials) {
        Map<String, Long> m = new TreeMap<>();
        minSerials.forEach((k, v) -> m.put(k.hex(), v));
        return new RevocationList(version, issuedAtMillis, m, List.of());
    }

    public long version() { return version; }
    public long issuedAt() { return issuedAt; }
    public List<Endorsement> endorsements() { return endorsements; }

    public Map<NodeId, Long> entries() {
        Map<NodeId, Long> out = new TreeMap<>(java.util.Comparator.comparing(NodeId::hex));
        minSerials.forEach((k, v) -> out.put(new NodeId(k), v));
        return out;
    }

    public boolean revokes(Certificate c) {
        Long min = minSerials.get(c.nodeId().hex());
        return min != null && c.serial() < min;
    }

    /** Whether this list keeps every entry of {@code earlier} at least as strict. */
    boolean covers(RevocationList earlier) {
        for (Map.Entry<String, Long> e : earlier.minSerials.entrySet()) {
            Long mine = minSerials.get(e.getKey());
            if (mine == null || mine < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    public byte[] body() {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            out.writeInt(MAGIC);
            out.writeByte(1);
            out.writeLong(version);
            out.writeLong(issuedAt);
            out.writeInt(minSerials.size());
            for (Map.Entry<String, Long> e : minSerials.entrySet()) { // sorted: canonical
                out.write(HexFormat.of().parseHex(e.getKey()));
                out.writeLong(e.getValue());
            }
            return buf.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public RevocationList endorse(SigningKey authority) {
        Endorsement e = new Endorsement(authority.publicKey(), authority.sign(DOMAIN, body()));
        return new RevocationList(version, issuedAt, minSerials, Endorsement.add(endorsements, e));
    }

    public RevocationList merge(RevocationList other) {
        if (!Arrays.equals(body(), other.body())) {
            throw new IllegalArgumentException("these are different revocation lists");
        }
        List<Endorsement> all = endorsements;
        for (Endorsement e : other.endorsements) {
            all = Endorsement.add(all, e);
        }
        return new RevocationList(version, issuedAt, minSerials, all);
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

    public static RevocationList decode(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != 1) {
                throw new IllegalArgumentException("not a revocation list");
            }
            long version = in.readLong();
            long issuedAt = in.readLong();
            int n = in.readInt();
            if (n < 0 || n > MAX_ENTRIES) {
                throw new IllegalArgumentException("bad revocation list size");
            }
            Map<String, Long> m = new TreeMap<>();
            for (int i = 0; i < n; i++) {
                byte[] id = in.readNBytes(32);
                if (id.length != 32) {
                    throw new IllegalArgumentException("truncated revocation list");
                }
                m.put(HexFormat.of().formatHex(id), in.readLong());
            }
            List<Endorsement> endorsements = Endorsement.read(in);
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing bytes");
            }
            return new RevocationList(version, issuedAt, m, endorsements);
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated revocation list", e);
        }
    }

    public String toText() {
        return TEXT_PREFIX + HexFormat.of().formatHex(encode());
    }

    public static RevocationList parse(String text) {
        String t = text.trim();
        if (!t.startsWith(TEXT_PREFIX)) {
            throw new IllegalArgumentException("not a noray revocation list");
        }
        return decode(HexFormat.of().parseHex(t.substring(TEXT_PREFIX.length())));
    }
}
