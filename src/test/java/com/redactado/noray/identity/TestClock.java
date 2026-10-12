package com.redactado.noray.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that only moves when told to. */
public final class TestClock extends Clock {
    private volatile long now = 1_800_000_000_000L;

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    @Override public long millis() { return now; }

    public void advance(Duration d) { now += d.toMillis(); }
}
