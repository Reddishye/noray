package com.redactado.noray.filesystem.vfs.journal;

import com.redactado.noray.filesystem.api.FsException;

import java.util.List;
import java.util.Objects;

/** Tells a listener when the journal is about to be compacted and when it was, so that history is not misread as loss. */
public final class NotifyingJournal implements NamespaceJournal {

    public interface Listener {
        void beforeRewrite();
        void afterRewrite();
    }

    private final NamespaceJournal delegate;
    private final Listener listener;

    public NotifyingJournal(NamespaceJournal delegate, Listener listener) {
        this.delegate = Objects.requireNonNull(delegate);
        this.listener = Objects.requireNonNull(listener);
    }

    @Override public List<NamespaceRecord> load() throws FsException { return delegate.load(); }
    @Override public void replay(java.util.function.Consumer<NamespaceRecord> sink) throws FsException { delegate.replay(sink); }
    @Override public void append(NamespaceRecord record) throws FsException { delegate.append(record); }
    @Override public long recordCount() { return delegate.recordCount(); }
    @Override public JournalState state() { return delegate.state(); }
    @Override public boolean protectsSecrets() { return delegate.protectsSecrets(); }

    @Override
    public void rewrite(List<NamespaceRecord> snapshot) throws FsException {
        listener.beforeRewrite();
        delegate.rewrite(snapshot);
        listener.afterRewrite();
    }

    @Override public void close() { delegate.close(); }
}
