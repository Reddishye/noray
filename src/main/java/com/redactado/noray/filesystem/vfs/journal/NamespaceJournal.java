package com.redactado.noray.filesystem.vfs.journal;

import com.redactado.noray.filesystem.api.FsException;

import java.util.List;

/**
 * Durable, append-only log of namespace changes (a write-ahead journal).
 *
 * <p>This is a storage SPI: implementations may add encryption or signing without any change to the
 * filesystem service. Implementations must make {@link #append} durable according to their own policy
 * before returning, and must not expose a partially written record to {@link #load}.
 */
public interface NamespaceJournal extends AutoCloseable {

    /** Reads every valid record, in the order it was appended. Must be called once, before any append. */
    List<NamespaceRecord> load() throws FsException;

    /**
     * Streams every valid record, in order, to {@code sink} without keeping them: what a namespace with millions of
     * entries needs at boot. Same rules as {@link #load()} (call once, before any append).
     */
    default void replay(java.util.function.Consumer<NamespaceRecord> sink) throws FsException {
        load().forEach(sink);
    }

    /** Durably appends a record. */
    void append(NamespaceRecord record) throws FsException;

    /** Number of records currently stored (loaded plus appended). */
    long recordCount();

    /** Atomically replaces the whole journal with the given snapshot. */
    void rewrite(List<NamespaceRecord> snapshot) throws FsException;

    /**
     * Whether records may carry secrets such as file keys: true if the journal is encrypted at rest (or keeps
     * nothing at all). The filesystem refuses to store secrets in a journal that does not.
     */
    default JournalState state() {
        return new JournalState(recordCount(), null);
    }

    default boolean protectsSecrets() {
        return false;
    }

    @Override
    void close();

    /** A journal that stores nothing; the namespace then lives in memory only. */
    static NamespaceJournal none() {
        return new NamespaceJournal() {
            @Override public List<NamespaceRecord> load() { return List.of(); }
            @Override public void append(NamespaceRecord record) { }
            @Override public long recordCount() { return 0; }
            @Override public void rewrite(List<NamespaceRecord> snapshot) { }
            @Override public void close() { }
            @Override public boolean protectsSecrets() { return true; }
        };
    }
}
