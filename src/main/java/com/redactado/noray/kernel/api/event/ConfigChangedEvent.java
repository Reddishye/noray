package com.redactado.noray.kernel.api.event;

import com.redactado.noray.kernel.api.Event;

import java.time.Instant;

public record ConfigChangedEvent(String key, String oldValue, String newValue, Instant timestamp) implements Event {
    public ConfigChangedEvent(String key, String oldValue, String newValue) {
        this(key, oldValue, newValue, Instant.now());
    }
}