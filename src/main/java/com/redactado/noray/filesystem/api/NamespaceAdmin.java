package com.redactado.noray.filesystem.api;

import com.redactado.noray.filesystem.vfs.VfsPath;

import java.util.Map;

/**
 * Quotas and fine-grained rules attached to directories, enforced on every mutation in the subtree.
 *
 * <p>Attributes ({@link #KEYS}); a missing value means "no rule". {@code readonly}, {@code deny.delete},
 * {@code deny.overwrite} and {@code max.file.bytes} are looked up the tree and the <i>nearest</i> directory that
 * defines them wins, so a subdirectory can relax what its parent set (for example {@code readonly=false}).
 * {@code quota.bytes} and {@code quota.files} are cumulative: every ancestor that has a quota must have room.
 */
public interface NamespaceAdmin {

    String QUOTA_BYTES = "quota.bytes";
    String QUOTA_FILES = "quota.files";
    String MAX_FILE_BYTES = "max.file.bytes";
    /** No create, write or delete in the subtree. */
    String READONLY = "readonly";
    /** Files can be created and written once, but not deleted. */
    String DENY_DELETE = "deny.delete";
    /** A file that already has content cannot be replaced. */
    String DENY_OVERWRITE = "deny.overwrite";

    java.util.Set<String> KEYS = java.util.Set.of(QUOTA_BYTES, QUOTA_FILES, MAX_FILE_BYTES, READONLY, DENY_DELETE,
            DENY_OVERWRITE);

    /** Content bytes and number of files in a subtree. */
    record Usage(long bytes, long files) {
    }

    /** Sets an attribute of a directory (or mount point); an empty value removes it. */
    void setAttr(VfsPath dir, String key, String value) throws FsException;

    /** The attributes set <i>on</i> {@code dir} itself. */
    Map<String, String> attrsOf(VfsPath dir) throws FsException;

    /** What the subtree under {@code dir} currently uses. */
    Usage usageOf(VfsPath dir) throws FsException;

    /**
     * Estimated raw bytes the subtree occupies on storage nodes (redundancy of each file's storage class included; see
     * {@code StoragePolicy.storedBytesEstimate}). Files outside the data engine count once.
     */
    default long storedBytesOf(VfsPath dir) throws FsException {
        return usageOf(dir).bytes();
    }

    /**
     * Recomputes the running quota counters from the namespace and returns how many were wrong. Counters should never
     * drift; this is the safety net the maintenance loop runs every cycle.
     */
    default int reconcileUsage() {
        return 0;
    }
}
