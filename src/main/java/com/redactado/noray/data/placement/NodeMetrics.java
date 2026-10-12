package com.redactado.noray.data.placement;

/**
 * Running estimate of how fast a node really is: an exponentially weighted moving average of the throughput of
 * its reads and writes (bytes over wall time, so network and disk are both included) and of its failure rate.
 */
public final class NodeMetrics {

    /** Samples needed before the measurement replaces the configured speed. */
    public static final int WARMUP_SAMPLES = 8;
    private static final double ALPHA = 0.1;
    private static final double MIN_MBPS = 0.05;

    private double mbps;
    private double failureRate;
    private long samples;

    /** Records one completed operation. {@code ok=false} counts towards the failure rate and is not timed. */
    public synchronized void record(long bytes, long nanos, boolean ok) {
        failureRate = samples == 0 && !ok ? 1.0 : failureRate + ALPHA * ((ok ? 0.0 : 1.0) - failureRate);
        if (!ok) {
            samples++;
            return;
        }
        double seconds = Math.max(nanos, 1_000) / 1e9;
        double sample = Math.max(MIN_MBPS, bytes / 1_048_576.0 / seconds);
        mbps = samples == 0 || mbps == 0 ? sample : mbps + ALPHA * (sample - mbps);
        samples++;
    }

    public synchronized long samples() {
        return samples;
    }

    public synchronized double failureRate() {
        return failureRate;
    }

    public synchronized double throughputMBps() {
        return mbps;
    }

    /**
     * The speed weight to use: the measurement (scaled down by the failure rate) once there are enough samples,
     * otherwise {@code configured}. Both are expressed in MB/s.
     */
    public synchronized double effectiveSpeed(double configured) {
        if (samples < WARMUP_SAMPLES || mbps <= 0) {
            return configured;
        }
        return Math.max(MIN_MBPS, mbps * (1.0 - Math.min(0.9, failureRate)));
    }
}
