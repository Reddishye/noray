package com.redactado.noray.net.onion;

import java.time.Duration;

/**
 * @param hops          relays per message (the message crosses {@code hops + 1} nodes counting the destination)
 * @param minHops       refuse to send with fewer relays than this (0 would mean sending directly, not anonymously)
 * @param maxDelay      each relay holds a cell for a random time up to this before forwarding, to blur timing
 * @param cellTtl       how long a cell stays valid; relays drop older ones
 * @param replayEntries how many recently seen cells a node remembers (fail closed when full)
 * @param maxMessage    largest message in bytes (bigger is refused; smaller ones are split into cells)
 * @param reassembly    how long a half-received message is kept
 */
public record OnionConfig(int hops, int minHops, Duration maxDelay, Duration cellTtl, int replayEntries,
                          int maxMessage, Duration reassembly) {

    public OnionConfig {
        if (hops < 0 || hops > OnionPacket.MAX_RELAYS) {
            throw new IllegalArgumentException("hops must be 0.." + OnionPacket.MAX_RELAYS);
        }
        if (minHops < 0 || minHops > hops) {
            throw new IllegalArgumentException("minHops must be 0..hops");
        }
        if (maxDelay.isNegative() || cellTtl.isNegative() || cellTtl.isZero() || replayEntries < 1
                || maxMessage < 1 || reassembly.isNegative() || reassembly.isZero()) {
            throw new IllegalArgumentException("invalid onion configuration");
        }
    }

    public static OnionConfig defaults() {
        return new OnionConfig(2, 1, Duration.ofMillis(50), Duration.ofMinutes(2), 200_000, 16 * 1024,
                Duration.ofSeconds(60));
    }

    public OnionConfig withHops(int hops, int minHops) {
        return new OnionConfig(hops, minHops, maxDelay, cellTtl, replayEntries, maxMessage, reassembly);
    }

    public OnionConfig withMaxDelay(Duration d) {
        return new OnionConfig(hops, minHops, d, cellTtl, replayEntries, maxMessage, reassembly);
    }

    public OnionConfig withCellTtl(Duration d) {
        return new OnionConfig(hops, minHops, maxDelay, d, replayEntries, maxMessage, reassembly);
    }

    public OnionConfig withReplayEntries(int n) {
        return new OnionConfig(hops, minHops, maxDelay, cellTtl, n, maxMessage, reassembly);
    }
}
