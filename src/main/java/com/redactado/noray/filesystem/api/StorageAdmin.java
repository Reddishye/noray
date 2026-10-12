package com.redactado.noray.filesystem.api;

import com.redactado.noray.data.GcReport;
import com.redactado.noray.filesystem.vfs.VfsPath;

/** Administration of how files of pool mounts are stored. */
public interface StorageAdmin {

    /** Sets the storage policy of a directory (inherited by everything below); an empty name restores inheritance. */
    void setPolicy(VfsPath dir, String policy) throws FsException;

    /** The policy name that applies to {@code path} (for a file, the one of its directory). */
    String policyOf(VfsPath path) throws FsException;

    /** Re-stores existing files under {@code dir} so that they match the policy that applies to them now. Returns files changed. */
    int applyPolicy(VfsPath dir) throws FsException;

    /** Name of the plain policy {@code file} is stored with right now ("" if it has no engine data yet). */
    String storageClassOf(VfsPath file) throws FsException;

    /**
     * Summary of a repair pass over every file of the pool mounts.
     *
     * @param filesFailed files that could not even be examined (manifest unreadable, no nodes to place pieces on)
     */
    record RepairSummary(int files, int filesRepaired, int filesFailed, int piecesRebuilt, int variantsRecreated,
                         int pagesRestored, int chunksLost) {
    }

    /** Summary of one automatic tiering pass. */
    record TierSummary(int evaluated, int demoted, int promoted, int failed) {
    }

    /**
     * Restores the redundancy of every pool file after nodes were lost or data went bad.
     *
     * @param scrub            read every blob back and verify it (slow, finds silent corruption)
     * @param unavailableIsLost treat every node that is unreachable right now as gone, instead of waiting for the
     *                          grace period to pass
     */
    RepairSummary repair(boolean scrub, boolean unavailableIsLost) throws FsException;

    /**
     * Deletes blobs that no file needs. Aborts without deleting anything if the set of needed blobs cannot be
     * established completely (a manifest that cannot be read).
     */
    GcReport gc(boolean dryRun) throws FsException;

    /** Moves files whose directory policy is an auto profile between hot and cold, by how much they are used. */
    TierSummary autoTier() throws FsException;

    /** Lets the node watch see the current availability of the nodes (the maintenance loop calls this). */
    void observeNodes();
}
