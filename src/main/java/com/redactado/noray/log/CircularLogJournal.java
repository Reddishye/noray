package com.redactado.noray.log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public final class CircularLogJournal {

    private final int capacity;
    private final LogEntry[] buffer;
    private final AtomicLong sequenceGenerator = new AtomicLong(0);
    private final ReentrantLock appendLock = new ReentrantLock();
    private final List<Consumer<LogEntry>> streamListeners = new CopyOnWriteArrayList<>();

    private int writeIndex = 0;
    private int count = 0;

    public CircularLogJournal(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new LogEntry[capacity];
    }

    public LogEntry append(LogLevel level, String source, String message, Throwable throwable) {
        long seq = sequenceGenerator.incrementAndGet();
        LogEntry entry = new LogEntry(seq, Instant.now(), level, source, message, throwable);

        appendLock.lock();
        try {
            buffer[writeIndex] = entry;
            writeIndex = (writeIndex + 1) % capacity;
            if (count < capacity) {
                count++;
            }
        } finally {
            appendLock.unlock();
        }

        for (Consumer<LogEntry> listener : streamListeners) {
            try {
                listener.accept(entry);
            } catch (Throwable ignored) {}
        }
        return entry;
    }

    public LogEntry append(LogLevel level, String source, String message) {
        return append(level, source, message, null);
    }

    public List<LogEntry> snapshot(int limit, LogLevel minLevel) {
        appendLock.lock();
        try {
            List<LogEntry> result = new ArrayList<>();
            int effectiveLimit = (limit <= 0) ? count : Math.min(limit, count);
            int startOffset = (writeIndex - count + capacity) % capacity;

            for (int i = 0; i < count; i++) {
                int index = (startOffset + i) % capacity;
                LogEntry entry = buffer[index];
                if (entry != null && (minLevel == null || minLevel.includes(entry.level()))) {
                    result.add(entry);
                }
            }

            if (result.size() > effectiveLimit) {
                return new ArrayList<>(result.subList(result.size() - effectiveLimit, result.size()));
            }
            return result;
        } finally {
            appendLock.unlock();
        }
    }

    public void addStreamListener(Consumer<LogEntry> listener) {
        streamListeners.add(Objects.requireNonNull(listener, "listener cannot be null"));
    }

    public void removeStreamListener(Consumer<LogEntry> listener) {
        streamListeners.remove(listener);
    }
}
