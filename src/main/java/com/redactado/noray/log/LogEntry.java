package com.redactado.noray.log;

import java.time.Instant;
import java.util.Objects;

public record LogEntry(
        long sequence,
        Instant timestamp,
        LogLevel level,
        String source,
        String message,
        Throwable throwable
) {
    public LogEntry {
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(level, "level cannot be null");
        Objects.requireNonNull(source, "source cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
    }
}
