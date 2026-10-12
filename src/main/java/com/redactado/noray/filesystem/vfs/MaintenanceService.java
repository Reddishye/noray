package com.redactado.noray.filesystem.vfs;

import com.redactado.noray.filesystem.api.StorageAdmin;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background upkeep of the data engine: watches node availability, repairs files whose nodes were lost for good,
 * moves files between hot and cold according to use, saves the access statistics and, if enabled, garbage-collects.
 * Garbage collection is destructive, so it is off unless explicitly enabled and runs only every few cycles.
 */
public final class MaintenanceService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("noray.fs.maintenance");

    private final StorageAdmin admin;
    private final DataBackend backend;
    private final Duration interval;
    private final boolean gcEnabled;
    private final int gcEveryCycles;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "noray-maintenance");
        t.setDaemon(true);
        return t;
    });
    private int cycle;

    public MaintenanceService(StorageAdmin admin, DataBackend backend, Duration interval, boolean gcEnabled,
                              int gcEveryCycles) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.gcEnabled = gcEnabled;
        this.gcEveryCycles = Math.max(1, gcEveryCycles);
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::runOnceQuietly, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void runOnceQuietly() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "maintenance cycle failed: " + e.getMessage());
        }
    }

    /** One full cycle; also usable directly (tests, an operator command). */
    public synchronized void runOnce() {
        cycle++;
        admin.observeNodes();
        var repair = admin.repair(false, false);
        if (repair.filesRepaired() > 0 || repair.chunksLost() > 0 || repair.filesFailed() > 0) {
            LOG.log(System.Logger.Level.INFO, "maintenance: " + repair);
        }
        var tier = admin.autoTier();
        if (tier.demoted() > 0 || tier.promoted() > 0 || tier.failed() > 0) {
            LOG.log(System.Logger.Level.INFO, "maintenance: " + tier);
        }
        backend.saveStats().run();
        if (admin instanceof com.redactado.noray.filesystem.api.NamespaceAdmin ns) {
            int corrected = ns.reconcileUsage();
            if (corrected > 0) {
                com.redactado.noray.kernel.metrics.Metrics.global().add("fs.quota.reconciled", corrected);
                LOG.log(System.Logger.Level.WARNING, "maintenance: corrected " + corrected + " drifted quota counter(s)");
            }
        }
        if (gcEnabled && cycle % gcEveryCycles == 0) {
            var gc = admin.gc(false);
            if (gc.blobsDeleted() > 0) {
                LOG.log(System.Logger.Level.INFO, "maintenance: " + gc);
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
