package com.redactado.noray.filesystem.vfs.journal;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Refuses every change to the namespace while the guard names a reason (a witness alarm: the node's own history was
 * rolled back or forked, so continuing to write would make the tampering permanent). Reading stays possible.
 */
public final class GuardedJournal implements NamespaceJournal {

    private final NamespaceJournal delegate;
    private final Supplier<String> blockedBecause;

    /** @param blockedBecause the reason writes are refused, or {@code null} when they are allowed */
    public GuardedJournal(NamespaceJournal delegate, Supplier<String> blockedBecause) {
        this.delegate = Objects.requireNonNull(delegate);
        this.blockedBecause = Objects.requireNonNull(blockedBecause);
    }

    private void check() {
        String why = blockedBecause.get();
        if (why != null) {
            throw new FsException(FsErrorCode.DENIED, "namespace is read-only: " + why);
        }
    }

    @Override public List<NamespaceRecord> load() throws FsException { return delegate.load(); }
    @Override public void replay(java.util.function.Consumer<NamespaceRecord> sink) throws FsException { delegate.replay(sink); }
    @Override public void append(NamespaceRecord record) throws FsException { check(); delegate.append(record); }
    @Override public void rewrite(List<NamespaceRecord> snapshot) throws FsException { check(); delegate.rewrite(snapshot); }
    @Override public long recordCount() { return delegate.recordCount(); }
    @Override public JournalState state() { return delegate.state(); }
    @Override public boolean protectsSecrets() { return delegate.protectsSecrets(); }
    @Override public void close() { delegate.close(); }
}
