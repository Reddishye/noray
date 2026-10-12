package com.redactado.noray.kernel.capability;

import com.redactado.noray.kernel.api.*;

import com.redactado.noray.kernel.api.EventListener;

import java.util.*;
import java.util.concurrent.*;

/**
 * What a sandboxed module sees instead of the real {@link KernelContext}.
 * Everything not granted is refused (and recorded); nothing here hands out the
 * raw kernel objects except where a grant says so.
 */
public final class RestrictedKernelContext implements KernelContext {

    private final KernelContext base;
    private final String moduleId;
    private final CapabilitySet grants;
    private final CapabilityRegistry registry;
    private final ServiceRegistry services;
    private final Configuration config;
    private final EventBus events;

    public RestrictedKernelContext(KernelContext base, String moduleId, CapabilitySet grants,
                                   CapabilityRegistry registry) {
        this.base = Objects.requireNonNull(base);
        this.moduleId = Objects.requireNonNull(moduleId);
        this.grants = Objects.requireNonNull(grants);
        this.registry = Objects.requireNonNull(registry);
        this.services = new GuardedServices();
        this.config = new GuardedConfig();
        this.events = new GuardedEvents();
    }

    public CapabilitySet grants() { return grants; }

    private CapabilityDeniedException deny(Capability c) {
        registry.deny(moduleId, c.toString());
        return new CapabilityDeniedException(moduleId, c.toString());
    }

    private void need(String kind, String arg) {
        Capability want = Capability.of(kind, arg);
        if (!grants.allows(want)) throw deny(want);
    }

    @Override public KernelPhase getPhase() { return base.getPhase(); }
    @Override public ServiceRegistry getServiceRegistry() { return services; }
    @Override public EventBus getEventBus() { return events; }
    @Override public Configuration getConfiguration() { return config; }

    @Override public ExecutorService getExecutionEngine() {
        need("executor", "");
        QuotaExecutor q = executor;
        if (q == null) {
            synchronized (this) {
                if (executor == null) {
                    var cfg = base.getConfiguration();
                    executor = new QuotaExecutor(base.getExecutionEngine(),
                            cfg.getInt("noray.module." + moduleId + ".max.tasks", 8),
                            cfg.getInt("noray.module." + moduleId + ".max.queued", 256),
                            cfg.getInt("noray.module." + moduleId + ".task.seconds.per.min", 0), moduleId);
                }
                q = executor;
            }
        }
        return q;
    }

    private volatile QuotaExecutor executor;

    /** A module may only register resources for itself. */
    @Override public void registerResource(String ignored, AutoCloseable resource) {
        base.registerResource(moduleId, resource);
    }

    /** Module instances are never handed to sandboxed code. */
    @Override public List<KernelModule> getActiveModules() {
        throw deny(Capability.of("kernel.modules", ""));
    }

    @Override public Set<String> getActiveModuleNames() {
        need("kernel.modules", "");
        return base.getActiveModuleNames();
    }

    // ---- services -----------------------------------------------------

    private final class GuardedServices implements ServiceRegistry {
        @Override public <T> void register(Class<T> type, T impl) {
            need("service.register", type.getSimpleName());
            if (base.getServiceRegistry().contains(type)) {
                // replacing a service would let a module impersonate it
                throw deny(Capability.of("service.register", type.getSimpleName() + " (already registered)"));
            }
            base.getServiceRegistry().register(type, impl);
        }

        @Override public <T> Optional<T> get(Class<T> type) {
            Optional<T> real = base.getServiceRegistry().get(type);
            Capability want = Capability.of("service", type.getSimpleName());
            if (grants.allows(want)) return real;
            ServiceAttenuator<T> att = registry.attenuatorFor(type);
            if (att != null && real.isPresent()) {
                T narrowed = att.attenuate(real.get(), moduleId, grants, registry);
                if (narrowed != null) return Optional.of(narrowed);
            }
            throw deny(want);
        }

        @Override public <T> T require(Class<T> type) {
            return get(type).orElseThrow(() ->
                    new com.redactado.noray.kernel.exception.ServiceNotFoundException(type));
        }

        @Override public <T> void unregister(Class<T> type) {
            throw deny(Capability.of("service.register", type.getSimpleName() + " (unregister)"));
        }

        /** Existence of services you cannot use is not revealed. */
        @Override public boolean contains(Class<?> type) {
            if (grants.allows("service", type.getSimpleName())) {
                return base.getServiceRegistry().contains(type);
            }
            return false;
        }
    }

    // ---- configuration --------------------------------------------------

    private final class GuardedConfig implements Configuration {
        @Override public String get(String key, String def) { need("config.read", key); return base.getConfiguration().get(key, def); }
        @Override public int getInt(String key, int def) { need("config.read", key); return base.getConfiguration().getInt(key, def); }
        @Override public boolean getBoolean(String key, boolean def) { need("config.read", key); return base.getConfiguration().getBoolean(key, def); }
        @Override public void set(String key, String value) { need("config.write", key); base.getConfiguration().set(key, value); }

        @Override public Set<String> getAllKeys() {
            Set<String> out = new TreeSet<>();
            for (String k : base.getConfiguration().getAllKeys()) {
                if (grants.allows("config.read", k)) out.add(k);
            }
            return out;
        }
    }

    // ---- events -----------------------------------------------------------

    private final class GuardedEvents implements EventBus {
        @Override public <E extends Event> void subscribe(Class<E> type, EventListener<E> l) {
            need("event.subscribe", type.getSimpleName());
            base.getEventBus().subscribe(type, l);
        }
        @Override public <E extends Event> void unsubscribe(Class<E> type, EventListener<E> l) {
            base.getEventBus().unsubscribe(type, l);
        }
        @Override public <E extends Event> void publish(E event) {
            need("event.publish", event.getClass().getSimpleName());
            base.getEventBus().publish(event);
        }
        @Override public <E extends Event> CompletableFuture<Void> publishAsync(E event) {
            need("event.publish", event.getClass().getSimpleName());
            return base.getEventBus().publishAsync(event);
        }
    }

    /**
     * The shared pool minus the ability to shut it down, and with a share: at most {@code maxRunning} of the module's
     * tasks run at once and at most {@code maxQueued} wait; beyond that submissions are rejected. A runaway module
     * therefore cannot starve the kernel's own work. With {@code task.seconds.per.min} the module also gets a time
     * budget: the wall time its tasks spend running is summed per minute, and past the budget new submissions are
     * refused until the minute is over. (Tasks run on virtual threads, where per-thread CPU time is not available, so
     * wall time is the measure; memory cannot be divided inside one JVM at all: that needs the process sandbox.)
     */
    static final class QuotaExecutor extends AbstractExecutorService {
        private static final System.Logger LOG = System.getLogger("noray.capability");
        private final ExecutorService pool;
        private final int maxRunning;
        private final int maxQueued;
        private final long budgetNanosPerMinute; // 0 = no time budget
        private final String moduleId;
        private final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        private final java.util.Map<Thread, Long> startedAt = new java.util.concurrent.ConcurrentHashMap<>();
        private int running;
        private long windowStart = System.nanoTime();
        private long spentInWindow;

        QuotaExecutor(ExecutorService pool, int maxRunning, int maxQueued) {
            this(pool, maxRunning, maxQueued, 0, "?");
        }

        QuotaExecutor(ExecutorService pool, int maxRunning, int maxQueued, int taskSecondsPerMinute, String moduleId) {
            this.pool = pool;
            this.maxRunning = Math.max(1, maxRunning);
            this.maxQueued = Math.max(0, maxQueued);
            this.budgetNanosPerMinute = Math.max(0, taskSecondsPerMinute) * 1_000_000_000L;
            this.moduleId = moduleId;
        }

        /** Task time used in the current minute, including tasks still running. */
        synchronized long spentNanos() {
            rollWindow();
            long now = System.nanoTime();
            long live = 0;
            for (long t : startedAt.values()) live += now - Math.max(t, windowStart);
            return spentInWindow + live;
        }

        private void rollWindow() {
            long now = System.nanoTime();
            if (now - windowStart >= 60_000_000_000L) {
                windowStart = now;
                spentInWindow = 0;
            }
        }

        @Override public void execute(Runnable r) {
            Objects.requireNonNull(r);
            if (budgetNanosPerMinute > 0 && spentNanos() > budgetNanosPerMinute) {
                com.redactado.noray.kernel.metrics.Metrics.global().inc("module.time.budget.refused");
                throw new java.util.concurrent.RejectedExecutionException("module [" + moduleId + "] used its task-time budget ("
                        + budgetNanosPerMinute / 1_000_000_000L + " s per minute)");
            }
            synchronized (this) {
                if (queue.size() >= maxQueued && running >= maxRunning) {
                    throw new java.util.concurrent.RejectedExecutionException(
                            "module task quota exceeded (" + maxRunning + " running, " + maxQueued + " queued)");
                }
                queue.add(r);
            }
            drain();
        }

        private void drain() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    if (running >= maxRunning || queue.isEmpty()) return;
                    next = queue.poll();
                    running++;
                }
                try {
                    pool.execute(() -> {
                        long t0 = System.nanoTime();
                        startedAt.put(Thread.currentThread(), t0);
                        try {
                            next.run();
                        } finally {
                            startedAt.remove(Thread.currentThread());
                            long took = System.nanoTime() - t0;
                            synchronized (QuotaExecutor.this) {
                                running--;
                                rollWindow();
                                spentInWindow += Math.min(took, System.nanoTime() - windowStart);
                            }
                            if (took > 60_000_000_000L) {
                                LOG.log(System.Logger.Level.WARNING, "module [" + moduleId + "] ran one task for "
                                        + took / 1_000_000_000L + " s");
                            }
                            drain();
                        }
                    });
                } catch (RuntimeException e) {
                    synchronized (this) { running--; }
                    throw e;
                }
            }
        }

        synchronized int running() { return running; }
        synchronized int queued() { return queue.size(); }

        @Override public void shutdown() { throw new SecurityException("The shared worker pool cannot be shut down"); }
        @Override public List<Runnable> shutdownNow() { throw new SecurityException("The shared worker pool cannot be shut down"); }
        @Override public boolean isShutdown() { return pool.isShutdown(); }
        @Override public boolean isTerminated() { return pool.isTerminated(); }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return false; }
        @Override public void close() { /* shared: closing is not up to the module */ }
    }
}
