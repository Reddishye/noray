package com.redactado.noray.kernel.metrics;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Counters and gauges of the node, cheap enough to leave on everywhere. Names are dotted lower case
 * ({@code net.bytes.out}); a counter only goes up, a gauge is read when asked for.
 *
 * <p>One registry per JVM ({@link #global()}): what is counted is the node's behaviour, not a module's, and several
 * modules add to the same names. Tests that run several nodes in one JVM see their sum.
 */
public final class Metrics {

    private static final Metrics GLOBAL = new Metrics();

    public static Metrics global() {
        return GLOBAL;
    }

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, LongSupplier> gauges = new ConcurrentHashMap<>();

    public void inc(String name) {
        add(name, 1);
    }

    public void add(String name, long delta) {
        counters.computeIfAbsent(name, n -> new LongAdder()).add(delta);
    }

    public long count(String name) {
        LongAdder a = counters.get(name);
        return a == null ? 0 : a.sum();
    }

    /** Registers (or replaces) a gauge. */
    public void gauge(String name, LongSupplier supplier) {
        gauges.put(name, supplier);
    }

    public void removeGauge(String name) {
        gauges.remove(name);
    }

    /** Everything, sorted by name. A gauge that throws is left out. */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        counters.forEach((k, v) -> out.put(k, v.sum()));
        gauges.forEach((k, v) -> {
            try {
                out.put(k, v.getAsLong());
            } catch (RuntimeException ignored) {
                // a gauge of something that is shutting down
            }
        });
        return out;
    }

    /** Prometheus text exposition format: {@code noray_net_bytes_out 123}. */
    public String prometheus() {
        StringBuilder sb = new StringBuilder();
        snapshot().forEach((k, v) -> {
            String n = "noray_" + k.replace('.', '_').replace('-', '_');
            boolean gauge = gauges.containsKey(k);
            sb.append("# TYPE ").append(n).append(gauge ? " gauge\n" : " counter\n");
            sb.append(n).append(' ').append(v).append('\n');
        });
        return sb.toString();
    }

    public String json() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> e : snapshot().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }
}
