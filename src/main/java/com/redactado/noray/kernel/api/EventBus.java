package com.redactado.noray.kernel.api;

import java.util.concurrent.CompletableFuture;

public interface EventBus {
    <E extends Event> void subscribe(Class<E> eventType, EventListener<E> listener);
    <E extends Event> void unsubscribe(Class<E> eventType, EventListener<E> listener);

    /** Dispatches the event synchronously to all registered listeners. */
    <E extends Event> void publish(E event);

    /** Dispatches the event asynchronously, returning a future completed when all listeners finish. */
    <E extends Event> CompletableFuture<Void> publishAsync(E event);
}