package com.redactado.noray.kernel.api;

public enum KernelPhase {
    PRE_BOOT,     // Initial logs and basic environment
    BOOT,         // Loading core modules
    POST_BOOT,    // State verifications and init hooks
    RUNNING,      // You retarded, read the name
    PRE_SHUTDOWN, // Stop new tasks
    SHUTDOWN,     // Resource freeing
    TERMINATED,   // You retarded x2
    PANIC         // I'm copying the Linux Kernel, so take your guess
}