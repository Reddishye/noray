package com.redactado.noray.net.overlay;

import java.util.Arrays;

/**
 * What an observer sees of an encrypted packet is its length and timing. Padding hides the length of what was sent
 * (a 40-byte "have you this blob?" and a 90-byte "yes" look the same), and cover frames hide when something was sent.
 *
 * <p>Inner frame, inside the AEAD: {@code channel(1) payload padding(zeros) padLength(2)}. Channel
 * {@value #COVER_CHANNEL} is cover traffic: valid, authenticated, and discarded by the receiver.
 */
public final class TrafficShape {

    /** No real {@code MeshChannel} may use this wire id. */
    public static final int COVER_CHANNEL = 0xFF;

    /** Never grow a datagram past this: it would risk fragmentation or the UDP limit. */
    static final int MAX_PADDED = 1400;

    private final int bucket;

    /** @param bucket sizes are rounded up to a multiple of this many bytes; 0 means no padding */
    public TrafficShape(int bucket) {
        if (bucket < 0 || bucket > MAX_PADDED) {
            throw new IllegalArgumentException("bucket must be between 0 and " + MAX_PADDED);
        }
        this.bucket = bucket;
    }

    public int bucket() {
        return bucket;
    }

    /** {@code channel + payload} → padded inner frame. */
    public byte[] pad(int channel, byte[] payload) {
        return pad(channel, payload, 0);
    }

    /** As {@link #pad(int, byte[])} but at least {@code minTotal} bytes long (used for cover frames). */
    byte[] pad(int channel, byte[] payload, int minTotal) {
        int core = 1 + payload.length + 2;
        int total = Math.max(core, minTotal);
        if (bucket > 0) {
            int rounded = ((total + bucket - 1) / bucket) * bucket;
            if (rounded <= MAX_PADDED) {
                total = rounded; // larger messages are left alone rather than risk fragmentation
            }
        }
        int padLen = total - core;
        byte[] out = new byte[total];
        out[0] = (byte) channel;
        System.arraycopy(payload, 0, out, 1, payload.length);
        out[total - 2] = (byte) (padLen >>> 8);
        out[total - 1] = (byte) padLen;
        return out;
    }

    /** @return channel byte followed by the payload; throws if the padding is not well formed */
    public static byte[] unpad(byte[] inner) {
        if (inner.length < 3) {
            throw new IllegalArgumentException("short frame");
        }
        int padLen = ((inner[inner.length - 2] & 0xFF) << 8) | (inner[inner.length - 1] & 0xFF);
        int payloadEnd = inner.length - 2 - padLen;
        if (padLen < 0 || payloadEnd < 1) {
            throw new IllegalArgumentException("bad padding");
        }
        for (int i = payloadEnd; i < inner.length - 2; i++) {
            if (inner[i] != 0) {
                throw new IllegalArgumentException("bad padding");
            }
        }
        return Arrays.copyOf(inner, payloadEnd);
    }

    public static boolean isCover(byte[] unpadded) {
        return (unpadded[0] & 0xFF) == COVER_CHANNEL;
    }
}
