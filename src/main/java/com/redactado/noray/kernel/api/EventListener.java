package com.redactado.noray.kernel.api;

@FunctionalInterface
public interface EventListener<E extends Event> {
    void onEvent(E event) throws Exception;
}