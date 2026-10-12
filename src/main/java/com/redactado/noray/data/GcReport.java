package com.redactado.noray.data;

import java.util.List;

/**
 * Outcome of {@link DataEngine#collectGarbage}.
 *
 * @param nodesSkipped nodes that could not be listed (unavailable, or a store that cannot enumerate its blobs)
 */
public record GcReport(int nodesScanned, long blobsScanned, long blobsDeleted, long bytesFreed, long blobsProtected,
                       List<String> nodesSkipped, boolean dryRun) {
}
