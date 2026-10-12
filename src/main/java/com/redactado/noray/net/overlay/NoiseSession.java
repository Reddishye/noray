package com.redactado.noray.net.overlay;

public final class NoiseSession {

    /** Replay window size in packets (power of two). Nonces older than {@code maxNonce - WINDOW + 1} are rejected. */
    static final int WINDOW_SIZE = 2048;
    private static final int WORDS = WINDOW_SIZE / Long.SIZE;

    private final byte[] sendKey;
    private final byte[] recvKey;
    private final byte[] handshakeHash;
    private long sendN;
    private long recvN;

    // Sliding-window bitmap (RFC 6479 style): bit (nonce mod WINDOW_SIZE) is set if that nonce was seen.
    private final long[] replayBitmap = new long[WORDS];
    private long maxNonce = -1L;

    public NoiseSession(byte[] sendKey, byte[] recvKey, byte[] handshakeHash) {
        this.sendKey = sendKey;
        this.recvKey = recvKey;
        this.handshakeHash = handshakeHash.clone();
    }

    /** Far below 2^64: the overlay replaces a session long before, and refuses to use one that got here. */
    static final long MAX_MESSAGES = 1L << 60;

    public byte[] encrypt(byte[] ad, byte[] plaintext) {
        if (sendN >= MAX_MESSAGES) {
            throw new IllegalStateException("session exhausted");
        }
        return NoiseCrypto.encrypt(sendKey, sendN++, ad, plaintext);
    }

    public synchronized byte[] decrypt(long nonce, byte[] ad, byte[] ciphertext) {
        if (!acceptable(nonce)) {
            throw new IllegalStateException("replay");
        }
        // Authenticate first: the window must only advance for packets that pass AEAD verification.
        byte[] p = NoiseCrypto.decrypt(recvKey, nonce, ad, ciphertext);
        markSeen(nonce);
        if (nonce >= recvN) recvN = nonce + 1;
        return p;
    }

    public long peekSendNonce() {
        return sendN;
    }

    /** True if the nonce is inside the window (or ahead of it) and has not been seen yet. */
    private boolean acceptable(long nonce) {
        if (nonce < 0) return false;
        if (nonce > maxNonce) return true;
        if (maxNonce - nonce >= WINDOW_SIZE) return false; // too old
        return !isSet(nonce);
    }

    private void markSeen(long nonce) {
        if (nonce > maxNonce) {
            long advance = nonce - maxNonce;
            if (advance >= WINDOW_SIZE) {
                java.util.Arrays.fill(replayBitmap, 0L);
            } else {
                // Clear the slots that the window slides over.
                for (long n = maxNonce + 1; n <= nonce; n++) {
                    clear(n);
                }
            }
            maxNonce = nonce;
        }
        set(nonce);
    }

    private static int slot(long nonce) {
        return (int) (nonce & (WINDOW_SIZE - 1));
    }

    private boolean isSet(long nonce) {
        int s = slot(nonce);
        return (replayBitmap[s >>> 6] & (1L << (s & 63))) != 0;
    }

    private void set(long nonce) {
        int s = slot(nonce);
        replayBitmap[s >>> 6] |= 1L << (s & 63);
    }

    private void clear(long nonce) {
        int s = slot(nonce);
        replayBitmap[s >>> 6] &= ~(1L << (s & 63));
    }
}
