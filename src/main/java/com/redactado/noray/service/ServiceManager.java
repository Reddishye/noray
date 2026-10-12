package com.redactado.noray.service;

import com.redactado.noray.log.CircularLogJournal;
import com.redactado.noray.log.LogLevel;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ServiceManager implements AutoCloseable {

    private final Map<String, ServiceDescriptor> registry = new ConcurrentHashMap<>();
    private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final ScheduledExecutorService supervisor;
    private final CircularLogJournal journal;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ServiceManager(CircularLogJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal cannot be null");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.supervisor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "noray-service-supervisor");
            t.setDaemon(true);
            return t;
        });
    }

    public void register(ServiceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        registry.put(descriptor.name(), descriptor);
        instances.putIfAbsent(descriptor.name(), new ServiceInstance(descriptor));
        journal.append(LogLevel.INFO, "ServiceManager", "Registered service unit: " + descriptor.name());
    }

    public synchronized void startService(String name) {
        ServiceDescriptor desc = registry.get(name);
        if (desc == null) {
            throw new IllegalArgumentException("Unknown service: " + name);
        }

        // Check and start dependencies first
        for (String dep : desc.dependencies()) {
            ServiceInstance depInst = instances.get(dep);
            if (depInst == null || depInst.status.get() != ServiceStatus.RUNNING) {
                journal.append(LogLevel.INFO, "ServiceManager", "Starting required dependency [" + dep + "] for [" + name + "]");
                startService(dep);
            }
        }

        ServiceInstance instance = instances.get(name);
        if (instance.status.get() == ServiceStatus.RUNNING || instance.status.get() == ServiceStatus.STARTING) {
            return;
        }

        launchInstance(instance);
    }

    public synchronized void stopService(String name) {
        ServiceInstance instance = instances.get(name);
        if (instance == null) {
            throw new IllegalArgumentException("Unknown service: " + name);
        }

        // Stop services that depend on this service first
        for (ServiceDescriptor desc : registry.values()) {
            if (desc.dependencies().contains(name)) {
                ServiceInstance dep = instances.get(desc.name());
                if (dep != null && dep.status.get() == ServiceStatus.RUNNING) {
                    journal.append(LogLevel.INFO, "ServiceManager", "Stopping dependent service [" + desc.name() + "] before [" + name + "]");
                    stopService(desc.name());
                }
            }
        }

        instance.stop();
    }

    public synchronized void restartService(String name) {
        stopService(name);
        startService(name);
    }

    public List<ServiceInfo> listServices() {
        List<ServiceInfo> list = new ArrayList<>();
        for (Map.Entry<String, ServiceDescriptor> entry : registry.entrySet()) {
            String name = entry.getKey();
            ServiceDescriptor desc = entry.getValue();
            ServiceInstance inst = instances.get(name);
            list.add(new ServiceInfo(
                    name,
                    desc.description(),
                    inst != null ? inst.status.get() : ServiceStatus.STOPPED,
                    inst != null ? inst.restartCount.get() : 0,
                    inst != null ? inst.startedAt : null,
                    inst != null ? inst.lastError : null
            ));
        }
        list.sort(Comparator.comparing(ServiceInfo::name));
        return Collections.unmodifiableList(list);
    }

    public ServiceInfo getServiceInfo(String name) {
        ServiceDescriptor desc = registry.get(name);
        if (desc == null) return null;
        ServiceInstance inst = instances.get(name);
        return new ServiceInfo(
                name,
                desc.description(),
                inst != null ? inst.status.get() : ServiceStatus.STOPPED,
                inst != null ? inst.restartCount.get() : 0,
                inst != null ? inst.startedAt : null,
                inst != null ? inst.lastError : null
        );
    }

    private void launchInstance(ServiceInstance instance) {
        instance.status.set(ServiceStatus.STARTING);
        instance.stopRequested.set(false);
        instance.startedAt = Instant.now();
        journal.append(LogLevel.INFO, "ServiceManager", "Starting unit: " + instance.descriptor.name());

        Future<?> task = executor.submit(() -> {
            instance.status.set(ServiceStatus.RUNNING);
            try {
                instance.descriptor.worker().run(new ServiceExecutionContext() {
                    @Override
                    public String getServiceName() {
                        return instance.descriptor.name();
                    }

                    @Override
                    public boolean isStopRequested() {
                        return instance.stopRequested.get();
                    }

                    @Override
                    public void log(String message) {
                        journal.append(LogLevel.INFO, instance.descriptor.name(), message);
                    }
                });

                if (instance.stopRequested.get()) {
                    instance.status.set(ServiceStatus.STOPPED);
                    journal.append(LogLevel.INFO, "ServiceManager", "Unit stopped cleanly: " + instance.descriptor.name());
                } else {
                    instance.status.set(ServiceStatus.STOPPED);
                    handleTermination(instance, null);
                }
            } catch (Throwable t) {
                instance.lastError = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                instance.status.set(ServiceStatus.FAILED);
                journal.append(LogLevel.ERROR, "ServiceManager", "Unit crashed: " + instance.descriptor.name() + " -> " + instance.lastError, t);
                handleTermination(instance, t);
            }
        });
        instance.futureRef.set(task);
    }

    private void handleTermination(ServiceInstance instance, Throwable cause) {
        if (!running.get() || instance.stopRequested.get()) {
            return;
        }

        RestartPolicy policy = instance.descriptor.restartPolicy();
        boolean shouldRestart = (policy == RestartPolicy.ALWAYS) || (policy == RestartPolicy.ON_FAILURE && cause != null);

        if (shouldRestart && instance.restartCount.incrementAndGet() <= instance.descriptor.maxRestartRetries()) {
            journal.append(LogLevel.WARN, "ServiceManager", String.format(
                    "Restarting unit [%s] in %s (attempt %d/%d)",
                    instance.descriptor.name(),
                    instance.descriptor.restartDelay(),
                    instance.restartCount.get(),
                    instance.descriptor.maxRestartRetries()));

            supervisor.schedule(() -> {
                if (running.get() && !instance.stopRequested.get()) {
                    launchInstance(instance);
                }
            }, instance.descriptor.restartDelay().toMillis(), TimeUnit.MILLISECONDS);
        } else if (shouldRestart) {
            journal.append(LogLevel.ERROR, "ServiceManager", "Unit [" + instance.descriptor.name() + "] exceeded max restart budget. Marked FAILED.");
            instance.status.set(ServiceStatus.FAILED);
        }
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) return;
        journal.append(LogLevel.INFO, "ServiceManager", "Shutting down all managed service units...");

        for (ServiceInstance instance : instances.values()) {
            instance.stop();
        }

        supervisor.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class ServiceInstance {
        final ServiceDescriptor descriptor;
        final AtomicReference<ServiceStatus> status = new AtomicReference<>(ServiceStatus.STOPPED);
        final AtomicBoolean stopRequested = new AtomicBoolean(false);
        final AtomicInteger restartCount = new AtomicInteger(0);
        final AtomicReference<Future<?>> futureRef = new AtomicReference<>(null);
        volatile Instant startedAt;
        volatile String lastError;

        ServiceInstance(ServiceDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        void stop() {
            stopRequested.set(true);
            status.set(ServiceStatus.STOPPING);
            Future<?> f = futureRef.get();
            if (f != null && !f.isDone()) {
                f.cancel(true);
            }
            status.set(ServiceStatus.STOPPED);
        }
    }
}
