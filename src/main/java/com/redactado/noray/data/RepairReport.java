package com.redactado.noray.data;

import com.redactado.noray.data.manifest.FileRef;

/**
 * Outcome of {@link DataEngine#repair}. When {@code changed} is true {@code ref} is a <b>new</b> reference (the
 * manifest records where pieces live, so moving a piece changes it); the caller must store it and may then drop
 * the old manifest with {@link DataEngine#deleteManifest}.
 *
 * @param piecesRebuilt    replicas or shards recreated on other nodes from the surviving ones
 * @param variantsRecreated variants too damaged to rebuild from themselves, recreated from another variant
 * @param pagesRestored    manifest page copies re-created
 * @param chunksLost       chunks with no readable variant left: data is gone unless the nodes come back
 */
public record RepairReport(FileRef ref, boolean changed, int chunks, int chunksDamaged, int piecesRebuilt,
                           int variantsRecreated, int pagesRestored, int chunksLost) {

    public boolean healthy() {
        return chunksDamaged == 0 && pagesRestored == 0;
    }
}
