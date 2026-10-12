package com.redactado.noray.filesystem.vfs.journal;

/**
 * Where a journal stands: how many records it holds and a hash of the newest one (empty when the journal is not
 * encrypted and therefore has no frame hash). This is what witnesses remember.
 */
public record JournalState(long records, byte[] newestHash) {
    public JournalState {
        newestHash = newestHash == null ? new byte[0] : newestHash.clone();
    }

    public boolean sameAs(JournalState o) {
        return records == o.records && java.util.Arrays.equals(newestHash, o.newestHash);
    }
}
