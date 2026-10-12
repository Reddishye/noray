package com.redactado.noray.web.dashboard;

import com.redactado.noray.kernel.metrics.Metrics;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Keeps the last few minutes of selected metrics (and JVM heap) for the dashboard's charts and sparklines. */
public final class MetricsHistory implements AutoCloseable {

    public static final int SAMPLES = 150;
    public static final int PERIOD_SECONDS = 2;

    private record Sample(long millis, Map<String, Long> values, long heapUsed) {
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "noray-web-metrics");
        t.setDaemon(true);
        return t;
    });

    public MetricsHistory() {
        sample();
        scheduler.scheduleAtFixedRate(this::sample, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    void sample() {
        Sample s = new Sample(System.currentTimeMillis(), Metrics.global().snapshot(),
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        synchronized (samples) {
            samples.addLast(s);
            while (samples.size() > SAMPLES) samples.removeFirst();
        }
    }

    /** Per-second rate of a counter between consecutive samples (oldest first). */
    public List<Double> rate(String counter) {
        List<Sample> copy = copy();
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < copy.size(); i++) {
            Sample a = copy.get(i - 1), b = copy.get(i);
            double secs = Math.max(0.001, (b.millis - a.millis) / 1000.0);
            long delta = b.values.getOrDefault(counter, 0L) - a.values.getOrDefault(counter, 0L);
            out.add(Math.max(0, delta) / secs);
        }
        return out;
    }

    /** Heap in use, MiB, oldest first. */
    public List<Double> heapMiB() {
        return copy().stream().map(s -> s.heapUsed / 1048576.0).toList();
    }

    /** Labels for the x axis: seconds ago ("-120s" ... "now"), one per rate point. */
    public List<String> labels() {
        List<Sample> copy = copy();
        List<String> out = new ArrayList<>();
        long last = copy.isEmpty() ? 0 : copy.getLast().millis;
        for (int i = 1; i < copy.size(); i++) {
            long ago = (last - copy.get(i).millis) / 1000;
            out.add(ago == 0 ? "now" : "-" + ago + "s");
        }
        return out;
    }

    private List<Sample> copy() {
        synchronized (samples) {
            return new ArrayList<>(samples);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
