package com.redactado.noray.identity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** One authority's signature over a document. A document is valid once enough distinct authorities endorsed it. */
public record Endorsement(byte[] key, byte[] signature) {

    static final int MAX_PER_DOCUMENT = 16;

    public Endorsement {
        if (key.length != Ed25519.KEY_SIZE || signature.length != Ed25519.SIG_SIZE) {
            throw new IllegalArgumentException("bad endorsement size");
        }
        key = key.clone();
        signature = signature.clone();
    }

    static List<Endorsement> add(List<Endorsement> existing, Endorsement e) {
        List<Endorsement> out = new ArrayList<>();
        for (Endorsement x : existing) {
            if (!java.util.Arrays.equals(x.key, e.key)) {
                out.add(x);
            }
        }
        out.add(e);
        if (out.size() > MAX_PER_DOCUMENT) {
            throw new IllegalArgumentException("too many endorsements");
        }
        return List.copyOf(out);
    }

    /** Number of distinct trusted keys with a valid signature over {@code body}. The same key counts once. */
    static int countValid(Collection<byte[]> trusted, String domain, byte[] body, List<Endorsement> endorsements) {
        List<byte[]> counted = new ArrayList<>();
        for (Endorsement e : endorsements) {
            boolean isTrusted = trusted.stream().anyMatch(t -> java.util.Arrays.equals(t, e.key));
            boolean seen = counted.stream().anyMatch(t -> java.util.Arrays.equals(t, e.key));
            if (isTrusted && !seen && Ed25519.verify(e.key, domain, body, e.signature)) {
                counted.add(e.key);
            }
        }
        return counted.size();
    }

    static void write(DataOutputStream out, List<Endorsement> list) throws IOException {
        out.writeByte(list.size());
        for (Endorsement e : list) {
            out.write(e.key);
            out.write(e.signature);
        }
    }

    static List<Endorsement> read(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte();
        if (n > MAX_PER_DOCUMENT) {
            throw new IllegalArgumentException("too many endorsements");
        }
        List<Endorsement> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] k = in.readNBytes(Ed25519.KEY_SIZE);
            byte[] s = in.readNBytes(Ed25519.SIG_SIZE);
            if (k.length != Ed25519.KEY_SIZE || s.length != Ed25519.SIG_SIZE) {
                throw new IllegalArgumentException("truncated endorsement");
            }
            out.add(new Endorsement(k, s));
        }
        return List.copyOf(out);
    }
}
