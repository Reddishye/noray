package com.redactado.noray.kernel.api;

import java.util.Collections;
import java.util.Set;

public interface KernelModule {
    String id();

    default Set<String> dependencies() {
        return Collections.emptySet();
    }

    default InitLevel getInitLevel() {
        return InitLevel.SUBSYSTEM;
    }

    default int getOrderWithinLevel() {
        return 0;
    }

    default boolean isCritical() {
        return false;
    }

    /**
     * What this module needs when it runs sandboxed (see {@code kernel.capability}).
     * It is a request, not a grant: the operator decides through
     * {@code noray.module.<id>.grants}. Trusted (built-in) modules ignore it.
     */
    default Set<String> requestedCapabilities() {
        return Collections.emptySet();
    }

    void init(KernelContext context) throws Exception;
    void shutdown(KernelContext context) throws Exception;
}