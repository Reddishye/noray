package com.redactado.noray.kernel.api;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public interface KernelContext {
    KernelPhase getPhase();
    ServiceRegistry getServiceRegistry();
    EventBus getEventBus();
    ExecutorService getExecutionEngine();
    Configuration getConfiguration();
    void registerResource(String moduleId, AutoCloseable resource);

    /** Returns an unmodifiable snapshot of currently active (initialized) modules in load order. */
    List<KernelModule> getActiveModules();

    /** Returns a set of active (initialized) module names. */
    Set<String> getActiveModuleNames();
}