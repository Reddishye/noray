package com.redactado.noray.kernel.core;

import com.redactado.noray.kernel.api.*;
import com.redactado.noray.kernel.capability.*;
import com.redactado.noray.kernel.exception.KernelException;
import com.redactado.noray.kernel.exception.ModuleInitException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class NorayKernel implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(NorayKernel.class.getName());

    /** The Noray kernel version. */
    public static final String VERSION = "1.0.0";

    /** The Noray kernel build timestamp (ISO-8601). */
    public static final String BUILD_TIME = java.time.Instant.now().toString();

    private final AtomicReference<KernelPhase> phase = new AtomicReference<>(KernelPhase.PRE_BOOT);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private final List<KernelModule> registeredModules = new CopyOnWriteArrayList<>();
    private final List<KernelModule> activeModules = new CopyOnWriteArrayList<>();
    private final Map<String, List<AutoCloseable>> moduleResources = new ConcurrentHashMap<>();
    private final Set<KernelHook> hooks = new CopyOnWriteArraySet<>();
    private final Set<String> sandboxed = ConcurrentHashMap.newKeySet();
    private final Map<String, KernelContext> sandboxContexts = new ConcurrentHashMap<>();
    private final CapabilityRegistry capabilities = new CapabilityRegistry();

    private final ExecutorService executionEngine;
    private final ServiceRegistry serviceRegistry;
    private final ConcurrentEventBus eventBus;
    private final DefaultConfiguration configuration;
    private final HealthWatchdog healthWatchdog;
    private final KernelContext context;
    private final Thread osShutdownHookThread;

    public NorayKernel() {
        this(Collections.emptyMap());
    }

    public NorayKernel(Map<String, String> initialProperties) {
        ThreadFactory threadFactory = Thread.ofVirtual()
                .name("noray-worker-", 0)
                .uncaughtExceptionHandler((thread, throwable) ->
                        handleSystemError(throwable, "UncaughtWorkerException:" + thread.getName()))
                .factory();

        this.executionEngine = Executors.newThreadPerTaskExecutor(threadFactory);
        this.serviceRegistry = new DefaultServiceRegistry();
        this.eventBus = new ConcurrentEventBus(this.executionEngine, this::handleSystemError);
        this.configuration = new DefaultConfiguration(initialProperties, this.eventBus);
        this.healthWatchdog = new HealthWatchdog(this::handleHealthFailure);
        this.context = new InternalKernelContext();
        this.serviceRegistry.register(CapabilityRegistry.class, capabilities);

        this.osShutdownHookThread = new Thread(() -> {
            KernelPhase p = phase.get();
            if (p != KernelPhase.SHUTDOWN && p != KernelPhase.TERMINATED) {
                shutdown();
            }
        }, "noray-os-shutdown-trap");

        try {
            Runtime.getRuntime().addShutdownHook(this.osShutdownHookThread);
        } catch (IllegalStateException ignored) {
            // JVM already shutting down
        }
    }

    public synchronized NorayKernel discoverModules() {
        return discoverModules(Thread.currentThread().getContextClassLoader());
    }

    public synchronized NorayKernel discoverModules(ClassLoader classLoader) {
        ClassLoader cl = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
        ServiceLoader<KernelModule> loader = ServiceLoader.load(KernelModule.class, cl);
        for (KernelModule module : loader) {
            registerModule(module);
        }
        return this;
    }

    public synchronized NorayKernel registerModule(KernelModule module) {
        Objects.requireNonNull(module, "Module cannot be null");
        if (phase.get() != KernelPhase.PRE_BOOT) {
            throw new IllegalStateException("Cannot register module [" + module.id() + "] after PRE_BOOT phase.");
        }
        for (KernelModule existing : registeredModules) {
            if (existing.id().equals(module.id())) {
                throw new IllegalStateException("Module with ID [" + module.id() + "] is already registered.");
            }
        }
        registeredModules.add(module);
        return this;
    }

    /**
     * Registers a module that does NOT get the real kernel context. It sees only
     * what {@code noray.module.<id>.grants} allows (nothing by default). Use this
     * for code that is not part of the core distribution.
     */
    public synchronized NorayKernel registerSandboxedModule(KernelModule module) {
        registerModule(module);
        sandboxed.add(module.id());
        return this;
    }

    /**
     * Loads a sandboxed module into the running kernel (hot load). Its dependencies must already be active. If its
     * {@code init} fails, everything it registered is cleaned up and the error is rethrown.
     */
    public synchronized void loadSandboxedModuleLive(KernelModule module) {
        Objects.requireNonNull(module, "module");
        if (phase.get() != KernelPhase.RUNNING) {
            throw new IllegalStateException("live loading needs a running kernel (phase " + phase.get() + ")");
        }
        String id = module.id();
        if (registeredModules.stream().anyMatch(m -> m.id().equals(id))) {
            throw new IllegalStateException("Module with ID [" + id + "] is already registered.");
        }
        Set<String> active = getContext().getActiveModuleNames();
        for (String dep : module.dependencies()) {
            if (!active.contains(dep)) throw new IllegalStateException("module [" + id + "] needs [" + dep + "], which is not active");
        }
        registeredModules.add(module);
        sandboxed.add(id);
        try {
            module.init(contextFor(module));
            activeModules.add(module);
            LOGGER.log(System.Logger.Level.INFO, "Module [{0}] loaded into the running kernel", id);
        } catch (Throwable t) {
            cleanupModuleResources(id);
            registeredModules.remove(module);
            sandboxed.remove(id);
            sandboxContexts.remove(id);
            throw new ModuleInitException(id, t);
        }
    }

    /**
     * Shuts a sandboxed module down and forgets it, so a new version can be loaded. Trusted (core) modules cannot be
     * unloaded, and neither can a module another active module depends on.
     *
     * @return false if no such module is loaded
     */
    public synchronized boolean unloadModule(String id) {
        KernelModule module = activeModules.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
        if (module == null) return false;
        if (!sandboxed.contains(id)) throw new IllegalStateException("module [" + id + "] is part of the core and cannot be unloaded");
        for (KernelModule other : activeModules) {
            if (other != module && other.dependencies().contains(id)) {
                throw new IllegalStateException("module [" + other.id() + "] depends on [" + id + "]");
            }
        }
        try {
            module.shutdown(contextFor(module));
        } catch (Throwable t) {
            handleSystemError(t, "ModuleShutdown:" + id);
        } finally {
            cleanupModuleResources(id);
            activeModules.remove(module);
            registeredModules.remove(module);
            sandboxed.remove(id);
            sandboxContexts.remove(id); // the next load reads its grants again
        }
        LOGGER.log(System.Logger.Level.INFO, "Module [{0}] unloaded", id);
        return true;
    }

    public CapabilityRegistry capabilities() {
        return capabilities;
    }

    /** The context a module is given: the real one, or a restricted view. */
    private KernelContext contextFor(KernelModule module) {
        if (!sandboxed.contains(module.id())) {
            return context;
        }
        return sandboxContexts.computeIfAbsent(module.id(), id -> {
            CapabilitySet grants = CapabilitySet.parse(configuration.get("noray.module." + id + ".grants", ""));
            capabilities.setGrants(id, grants);
            for (String wanted : module.requestedCapabilities()) {
                if (!grants.allows(Capability.parse(wanted))) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Sandboxed module [{0}] asks for ''{1}'' which is not granted", id, wanted);
                }
            }
            LOGGER.log(System.Logger.Level.INFO, "Module [{0}] runs sandboxed with: {1}", id,
                    grants.isEmpty() ? "(nothing)" : grants.toString());
            return new RestrictedKernelContext(context, id, grants, capabilities);
        });
    }

    public synchronized NorayKernel registerHook(KernelHook hook) {
        hooks.add(Objects.requireNonNull(hook, "Hook cannot be null"));
        return this;
    }

    public void registerHealthCheck(HealthCheck check) {
        healthWatchdog.registerCheck(check);
    }

    public synchronized void boot() {
        if (!phase.compareAndSet(KernelPhase.PRE_BOOT, KernelPhase.BOOT)) {
            throw new IllegalStateException("Kernel already booted or in invalid phase: " + phase.get());
        }

        long bootStartTime = System.nanoTime();
        LOGGER.log(System.Logger.Level.INFO, "Starting Noray Kernel boot sequence...");

        try {
            notifyHooksBoot();

            List<KernelModule> executionOrder = DependencyResolver.resolveExecutionOrder(registeredModules);
            LOGGER.log(System.Logger.Level.INFO, "Resolved module dependency DAG for {0} modules", executionOrder.size());

            for (KernelModule module : executionOrder) {
                long moduleStartTime = System.nanoTime();
                LOGGER.log(System.Logger.Level.INFO, "Initializing module [{0}] (Level: {1})...",
                        module.id(), module.getInitLevel());

                try {
                    module.init(contextFor(module));
                    activeModules.add(module);
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - moduleStartTime);
                    LOGGER.log(System.Logger.Level.INFO, "Module [{0}] initialized in {1} ms", module.id(), elapsedMs);
                } catch (Throwable t) {
                    ModuleInitException initException = new ModuleInitException(module.id(), t);
                    handleSystemError(initException, "ModuleInitialization:" + module.id());
                    if (module.isCritical()) {
                        throw initException;
                    }
                }
            }

            phase.set(KernelPhase.RUNNING);
            healthWatchdog.start(30, TimeUnit.SECONDS);

            long totalBootMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bootStartTime);
            LOGGER.log(System.Logger.Level.INFO, "Kernel boot sequence completed in {0} ms. System state: RUNNING", totalBootMs);

        } catch (Throwable t) {
            panic(t);
        }
    }

    public void awaitShutdown() {
        KernelPhase currentPhase = phase.get();
        if (currentPhase == KernelPhase.TERMINATED) {
            return; // Already shut down cleanly
        }
        if (currentPhase != KernelPhase.RUNNING && currentPhase != KernelPhase.BOOT) {
            throw new IllegalStateException("Cannot await shutdown when kernel phase is " + currentPhase);
        }

        LOGGER.log(System.Logger.Level.DEBUG, "Execution thread awaiting termination signal...");
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(System.Logger.Level.WARNING, "Thread interrupted awaiting shutdown. Triggering shutdown.");
            shutdown();
        }
    }

    public synchronized void shutdown() {
        KernelPhase current = phase.get();
        if (current == KernelPhase.TERMINATED || current == KernelPhase.SHUTDOWN || current == KernelPhase.PRE_SHUTDOWN) {
            return;
        }

        LOGGER.log(System.Logger.Level.INFO, "Initiating graceful shutdown sequence...");
        phase.set(KernelPhase.PRE_SHUTDOWN);

        List<KernelModule> reverseModules = new ArrayList<>(activeModules);
        Collections.reverse(reverseModules);

        for (KernelModule module : reverseModules) {
            try {
                LOGGER.log(System.Logger.Level.INFO, "Unloading module [{0}]...", module.id());
                module.shutdown(contextFor(module));
            } catch (Throwable t) {
                handleSystemError(t, "ModuleShutdown:" + module.id());
            } finally {
                cleanupModuleResources(module.id());
            }
        }
        activeModules.clear();
        // resources filed under names that are not modules (the bootstrap's keyring, the service manager)
        for (String owner : new ArrayList<>(moduleResources.keySet())) {
            cleanupModuleResources(owner);
        }

        for (KernelHook hook : hooks) {
            try {
                hook.onShutdown();
            } catch (Throwable t) {
                handleSystemError(t, "KernelHook:onShutdown");
            }
        }

        phase.set(KernelPhase.SHUTDOWN);
        healthWatchdog.close();
        executionEngine.shutdown();

        try {
            if (!executionEngine.awaitTermination(5, TimeUnit.SECONDS)) {
                executionEngine.shutdownNow();
            }
        } catch (InterruptedException e) {
            executionEngine.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            Runtime.getRuntime().removeShutdownHook(this.osShutdownHookThread);
        } catch (IllegalStateException ignored) {
            // Already shutting down in JVM
        }

        phase.set(KernelPhase.TERMINATED);
        LOGGER.log(System.Logger.Level.INFO, "Noray Kernel halted cleanly.");

        shutdownLatch.countDown();
    }

    public void registerModuleResource(String moduleId, AutoCloseable resource) {
        Objects.requireNonNull(moduleId, "Module ID cannot be null");
        Objects.requireNonNull(resource, "Resource cannot be null");
        moduleResources.computeIfAbsent(moduleId, k -> new CopyOnWriteArrayList<>()).add(resource);
    }

    private void cleanupModuleResources(String moduleId) {
        List<AutoCloseable> resources = moduleResources.remove(moduleId);
        if (resources != null) {
            for (AutoCloseable resource : resources) {
                try {
                    resource.close();
                } catch (Throwable t) {
                    handleSystemError(t, "ResourceCleanup:" + moduleId);
                }
            }
        }
    }

    public void handleSystemError(Throwable throwable, String sourceContext) {
        for (KernelHook hook : hooks) {
            try {
                hook.onError(throwable, sourceContext);
            } catch (Throwable ignored) {}
        }
        // the root cause is what an operator needs ("port in use", "bad keystore"), not the wrapper
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String cause = root == throwable ? "" : " (cause: " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
        LOGGER.log(System.Logger.Level.ERROR, "[" + sourceContext + "] " + throwable.getClass().getSimpleName() + ": "
                + throwable.getMessage() + cause, throwable);
    }

    private void handleHealthFailure(String name, HealthCheck.HealthStatus status) {
        LOGGER.log(System.Logger.Level.WARNING, "Health check ''{0}'' failed: {1}", name, status.details());
        if (status.cause() != null) {
            handleSystemError(status.cause(), "HealthWatchdog:" + name);
        }
    }

    private void panic(Throwable rootCause) {
        phase.set(KernelPhase.PANIC);
        LOGGER.log(System.Logger.Level.ERROR, "Kernel Panic: fatal error during boot sequence!", rootCause);
        shutdown();
        throw new KernelException("Kernel Panic Triggered", rootCause);
    }

    private void notifyHooksBoot() {
        for (KernelHook hook : hooks) {
            try {
                hook.onBoot();
            } catch (Throwable t) {
                handleSystemError(t, "KernelHook:onBoot");
            }
        }
    }

    public KernelContext getContext() {
        return context;
    }

    @Override
    public void close() {
        shutdown();
    }

    private class InternalKernelContext implements KernelContext {
        @Override public KernelPhase getPhase() { return phase.get(); }
        @Override public ServiceRegistry getServiceRegistry() { return serviceRegistry; }
        @Override public EventBus getEventBus() { return eventBus; }
        @Override public ExecutorService getExecutionEngine() { return executionEngine; }
        @Override public Configuration getConfiguration() { return configuration; }
        @Override public void registerResource(String moduleId, AutoCloseable resource) {
            registerModuleResource(moduleId, resource);
        }
        @Override public List<KernelModule> getActiveModules() {
            return Collections.unmodifiableList(new ArrayList<>(activeModules));
        }
        @Override public Set<String> getActiveModuleNames() {
            return activeModules.stream()
                    .map(KernelModule::id)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}