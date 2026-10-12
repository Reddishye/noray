package com.redactado.noray.filesystem.store.remote;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.NodeId;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps how many bytes one peer may store per minute. It does not replace quotas, but it bounds how fast a
 * compromised (and still certified) peer can fill this node's disk, which gives the operator time to revoke it.
 */
public final class PeerRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private final long bytesPerMinute;
    private final Clock clock;
    private final Map<NodeId, long[]> windows = new ConcurrentHashMap<>(); // {windowStart, used}

    public PeerRateLimiter(long bytesPerMinute, Clock clock) {
        if (bytesPerMinute < 1) {
            throw new IllegalArgumentException("bytesPerMinute must be positive");
        }
        this.bytesPerMinute = bytesPerMinute;
        this.clock = clock;
    }

    /** @throws FsException {@code DENIED} when this peer has used up its budget for the current minute */
    public void charge(NodeId peer, long bytes) throws FsException {
        long now = clock.millis();
        long[] w = windows.computeIfAbsent(peer, p -> new long[]{now, 0});
        synchronized (w) {
            if (now - w[0] >= WINDOW_MILLIS) {
                w[0] = now;
                w[1] = 0;
            }
            if (w[1] + bytes > bytesPerMinute) {
                throw new FsException(FsErrorCode.DENIED, "peer exceeded its write budget of " + bytesPerMinute + " bytes/minute");
            }
            w[1] += bytes;
        }
    }
}
