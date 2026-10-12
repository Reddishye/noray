package com.redactado.noray.net.onion;

import java.util.HashSet;
import java.util.Set;

/**
 * Remembers the ephemeral keys of recently peeled cells, so that a recorded cell fed in again is not processed
 * twice. Two generations are rotated every {@code window}: an entry lives between one and two windows, which covers
 * every cell that has not expired yet. When full it refuses new cells instead of forgetting old ones.
 */
final class ReplayFilter {

    private record Key(long a, long b) {
    }

    private final long windowMillis;
    private final int capacity;
    private Set<Key> current = new HashSet<>();
    private Set<Key> previous = new HashSet<>();
    private long rotatedAt;
    private boolean started;

    ReplayFilter(long windowMillis, int capacity) {
        this.windowMillis = windowMillis;
        this.capacity = capacity;
    }

    /** @return true if the cell is new and was recorded; false if it is a replay or the filter is full */
    synchronized boolean firstSeen(byte[] ephemeral, long nowMillis) {
        if (!started) {
            started = true;
            rotatedAt = nowMillis;
        }
        if (nowMillis - rotatedAt >= windowMillis) {
            previous = nowMillis - rotatedAt >= 2 * windowMillis ? new HashSet<>() : current;
            current = new HashSet<>();
            rotatedAt = nowMillis;
        }
        Key k = new Key(readLong(ephemeral, 0), readLong(ephemeral, 8));
        if (current.contains(k) || previous.contains(k)) {
            return false;
        }
        if (current.size() + previous.size() >= capacity) {
            return false;
        }
        current.add(k);
        return true;
    }

    private static long readLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xff);
        }
        return v;
    }

    synchronized int size() {
        return current.size() + previous.size();
    }
}
