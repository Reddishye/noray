package com.redactado.noray.data.tier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers how much each file is used, as an exponentially decayed access count (half-life configurable), so
 * recent activity weighs more than old activity and no history has to be kept.
 *
 * <p>{@link #ratePerDay} converts the decayed count to an approximate steady "accesses per day", which is what
 * the tiering thresholds are expressed in.
 */
public final class AccessTracker {

    private record Entry(double score, long updatedAt, long lastAccess) {
    }

    private static final int MAGIC = 0x4e525948; // "NRYH"
    private static final int MAX_ENTRIES = 50_000_000;

    private final Clock clock;
    private final double halfLifeMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile long startedAt;

    public AccessTracker(Clock clock, Duration halfLife) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (halfLife.isZero() || halfLife.isNegative()) {
            throw new IllegalArgumentException("halfLife must be positive");
        }
        this.halfLifeMillis = halfLife.toMillis();
        this.startedAt = clock.millis();
    }

    private double decayed(Entry e, long now) {
        return e.score * Math.pow(0.5, Math.max(0, now - e.updatedAt) / halfLifeMillis);
    }

    public void record(String key) {
        long now = clock.millis();
        entries.merge(key, new Entry(1.0, now, now),
                (old, ignored) -> new Entry(decayed(old, now) + 1.0, now, now));
    }

    public void forget(String key) {
        entries.remove(key);
    }

    /** Approximate accesses per day, 0 if the key was never seen. */
    public double ratePerDay(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            return 0;
        }
        double halfLifeDays = halfLifeMillis / 86_400_000.0;
        return decayed(e, clock.millis()) * Math.log(2) / halfLifeDays;
    }

    /** Milliseconds since the last access, or since this tracker started if the key is unknown. */
    public long idleMillis(String key) {
        Entry e = entries.get(key);
        long ref = e == null ? startedAt : Math.max(e.lastAccess, startedAt);
        return Math.max(0, clock.millis() - ref);
    }

    /**
     * How long this tracker has been observing, counting persisted history. Tiering decisions that depend on
     * "has not been used" are only meaningful once this exceeds the idle threshold.
     */
    public long observedMillis() {
        return Math.max(0, clock.millis() - startedAt);
    }

    public int size() {
        return entries.size();
    }

    public byte[] export() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(MAGIC);
            out.writeLong(startedAt);
            out.writeInt(entries.size());
            for (Map.Entry<String, Entry> e : new HashMap<>(entries).entrySet()) {
                out.writeUTF(e.getKey());
                out.writeDouble(e.getValue().score);
                out.writeLong(e.getValue().updatedAt);
                out.writeLong(e.getValue().lastAccess);
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Merges a snapshot written by {@link #export()}; the observation start moves back to the oldest one. */
    public void importFrom(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("not an access snapshot");
            }
            long persistedStart = in.readLong();
            int n = in.readInt();
            if (n < 0 || n > MAX_ENTRIES) {
                throw new IllegalArgumentException("invalid entry count");
            }
            for (int i = 0; i < n; i++) {
                String key = in.readUTF();
                entries.putIfAbsent(key, new Entry(in.readDouble(), in.readLong(), in.readLong()));
            }
            startedAt = Math.min(startedAt, persistedStart);
        } catch (IOException e) {
            throw new IllegalArgumentException("corrupt access snapshot", e);
        }
    }
}
