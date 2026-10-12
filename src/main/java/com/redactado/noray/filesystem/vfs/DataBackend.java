package com.redactado.noray.filesystem.vfs;

import com.redactado.noray.data.DataEngine;
import com.redactado.noray.data.placement.DataCluster;
import com.redactado.noray.data.placement.NodeWatch;
import com.redactado.noray.data.tier.AccessTracker;
import com.redactado.noray.data.tier.PolicyCatalog;

import java.time.Duration;
import java.util.Objects;

/**
 * Everything the filesystem needs to store the files of pool mounts through the data engine.
 *
 * @param cluster  the nodes the engine uses (needed to judge which are lost)
 * @param watch    tells brief outages from lost nodes
 * @param gcGrace  garbage collection never touches blobs written more recently than this
 * @param saveStats persists the access statistics (may be a no-op)
 */
public record DataBackend(DataEngine engine, PolicyCatalog catalog, AccessTracker tracker, DataCluster cluster,
                          NodeWatch watch, Duration gcGrace, Runnable saveStats) {
    public DataBackend {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(cluster, "cluster");
        Objects.requireNonNull(watch, "watch");
        Objects.requireNonNull(gcGrace, "gcGrace");
        Objects.requireNonNull(saveStats, "saveStats");
    }
}
