package com.redactado.noray.kernel.api;

public interface KernelHook {
    default void onBoot() throws Exception {}
    default void onShutdown() throws Exception {}
    default void onError(Throwable throwable, String context) {}
}