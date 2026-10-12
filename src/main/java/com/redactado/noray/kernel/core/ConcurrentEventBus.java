package com.redactado.noray.kernel.core;

import com.redactado.noray.kernel.api.Event;
import com.redactado.noray.kernel.api.EventBus;
import com.redactado.noray.kernel.api.EventListener;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

public final class ConcurrentEventBus implements EventBus {

    private static final int MAX_DEAD_LETTER_CAPACITY = 1000;

    private final Map<Class<? extends Event>, Set<EventListener<?>>> listeners = new ConcurrentHashMap<>();
    private final Queue<Event> deadLetterQueue = new ConcurrentLinkedQueue<>();
    private final ExecutorService executor;
    private final BiConsumer<Throwable, String> errorHandler;

    public ConcurrentEventBus(ExecutorService executor, BiConsumer<Throwable, String> errorHandler) {
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null");
        this.errorHandler = Objects.requireNonNull(errorHandler, "Error handler cannot be null");
    }

    @Override
    public <E extends Event> void subscribe(Class<E> eventType, EventListener<E> listener) {
        Objects.requireNonNull(eventType, "Event type cannot be null");
        Objects.requireNonNull(listener, "Listener cannot be null");
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArraySet<>()).add(listener);
    }

    @Override
    public <E extends Event> void unsubscribe(Class<E> eventType, EventListener<E> listener) {
        Objects.requireNonNull(eventType, "Event type cannot be null");
        Objects.requireNonNull(listener, "Listener cannot be null");
        Set<EventListener<?>> targets = listeners.get(eventType);
        if (targets != null) {
            targets.remove(listener);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> void publish(E event) {
        Objects.requireNonNull(event, "Event cannot be null");
        List<EventListener<?>> matchingListeners = findMatchingListeners(event.getClass());

        if (matchingListeners.isEmpty()) {
            recordDeadLetter(event);
            return;
        }

        for (EventListener<?> listener : matchingListeners) {
            try {
                ((EventListener<E>) listener).onEvent(event);
            } catch (Throwable t) {
                errorHandler.accept(t, "EventBus:sync:" + event.getClass().getSimpleName());
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> CompletableFuture<Void> publishAsync(E event) {
        Objects.requireNonNull(event, "Event cannot be null");
        List<EventListener<?>> matchingListeners = findMatchingListeners(event.getClass());

        if (matchingListeners.isEmpty()) {
            recordDeadLetter(event);
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> tasks = new ArrayList<>(matchingListeners.size());
        for (EventListener<?> listener : matchingListeners) {
            EventListener<E> typedListener = (EventListener<E>) listener;
            tasks.add(CompletableFuture.runAsync(() -> {
                try {
                    typedListener.onEvent(event);
                } catch (Throwable t) {
                    errorHandler.accept(t, "EventBus:async:" + event.getClass().getSimpleName());
                }
            }, executor));
        }

        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
    }

    private List<EventListener<?>> findMatchingListeners(Class<?> eventClass) {
        List<EventListener<?>> matched = new ArrayList<>();
        for (Map.Entry<Class<? extends Event>, Set<EventListener<?>>> entry : listeners.entrySet()) {
            if (entry.getKey().isAssignableFrom(eventClass)) {
                matched.addAll(entry.getValue());
            }
        }
        return matched;
    }

    private void recordDeadLetter(Event event) {
        if (deadLetterQueue.size() >= MAX_DEAD_LETTER_CAPACITY) {
            deadLetterQueue.poll();
        }
        deadLetterQueue.offer(event);
    }

    public Queue<Event> getDeadLetterQueue() {
        return deadLetterQueue;
    }
}