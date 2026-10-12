package com.redactado.noray.identity.witness;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * What a node announces about its namespace journal: how many records it has and a hash of the newest one.
 * {@code COMPACTING} says "the journal is about to be rewritten, counts reset": nothing is compared against it.
 */
public record Checkpoint(Kind kind, long records, byte[] hash) {

    public enum Kind { STATE, COMPACTING }

    public Checkpoint {
        hash = hash == null ? new byte[0] : hash.clone();
        if (hash.length > 32) throw new IllegalArgumentException("hash too long");
    }

    public byte[] encode() {
        return ByteBuffer.allocate(1 + 8 + 1 + hash.length).put((byte) kind.ordinal()).putLong(records)
                .put((byte) hash.length).put(hash).array();
    }

    public static Checkpoint decode(byte[] b) {
        if (b.length < 10) throw new IllegalArgumentException("short checkpoint");
        ByteBuffer in = ByteBuffer.wrap(b);
        int k = in.get();
        if (k < 0 || k >= Kind.values().length) throw new IllegalArgumentException("unknown checkpoint kind");
        long records = in.getLong();
        int n = in.get() & 0xFF;
        if (n > 32 || in.remaining() != n) throw new IllegalArgumentException("bad checkpoint hash");
        byte[] h = new byte[n];
        in.get(h);
        return new Checkpoint(Kind.values()[k], records, h);
    }

    public boolean sameStateAs(long otherRecords, byte[] otherHash) {
        return kind == Kind.STATE && records == otherRecords && Arrays.equals(hash, otherHash);
    }
}
