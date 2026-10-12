package com.redactado.noray.filesystem.volume;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Keeps the state of the volumes in line with reality: a remote node that stops answering goes OFFLINE (so reads and
 * writes skip it at once instead of waiting for timeouts), and comes back ONLINE when it answers again.
 */
public final class VolumeHealthMonitor implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(VolumeHealthMonitor.class.getName());

    private final VolumeManager manager;
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Runnable beforeEachRound = () -> { };
    private volatile boolean running;
    private Thread thread;

    public VolumeHealthMonitor(VolumeManager manager) {
        this.manager = manager;
        var m = com.redactado.noray.kernel.metrics.Metrics.global();
        m.gauge("volumes.total", () -> manager.list().size());
        m.gauge("volumes.online", () -> manager.list().stream().filter(v -> v.state() == VolumeState.ONLINE).count());
        m.gauge("volumes.offline", () -> manager.list().stream().filter(v -> v.state() == VolumeState.OFFLINE).count());
    }

    /** Something to do at the start of every round (for example re-attaching volumes that were not available at boot). */
    public void beforeEachRound(Runnable action) {
        this.beforeEachRound = action;
    }

    public synchronized void start(Duration interval) {
        if (running) return;
        running = true;
        thread = Thread.ofPlatform().daemon().name("noray-volume-health").start(() -> {
            while (running) {
                try {
                    Thread.sleep(interval.toMillis());
                    checkNow();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "volume health check failed", e);
                }
            }
        });
    }

    /** Probes every volume (in parallel) and updates states; returns when all probes are done. */
    public void checkNow() {
        try {
            beforeEachRound.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "retrying pending volumes failed", e);
        }
        List<Future<?>> all = new ArrayList<>();
        for (Volume v : manager.list()) {
            VolumeState s = v.state();
            if (s != VolumeState.ONLINE && s != VolumeState.DEGRADED && s != VolumeState.OFFLINE) continue;
            all.add(probes.submit(() -> check(v)));
        }
        for (Future<?> f : all) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException ignored) {
                // check() reports its own failures
            }
        }
    }

    private void check(Volume v) {
        boolean healthy;
        try {
            healthy = v.driver().isHealthy(v);
        } catch (RuntimeException e) {
            healthy = false;
        }
        try {
            if (healthy && v.state() != VolumeState.ONLINE) {
                manager.markOnline(v.id());
            } else if (!healthy && v.state() != VolumeState.OFFLINE) {
                manager.markOffline(v.id(), "no answer from the volume");
                com.redactado.noray.kernel.metrics.Metrics.global().inc("volumes.offline.events");
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "state update skipped for " + v.id(), e);
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) thread.interrupt();
        probes.shutdownNow();
    }
}
