package com.redactado.noray.kernel.core;

import com.redactado.noray.kernel.api.Configuration;
import com.redactado.noray.kernel.api.EventBus;
import com.redactado.noray.kernel.api.event.ConfigChangedEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultConfiguration implements Configuration {

    private final Map<String, String> properties = new ConcurrentHashMap<>();
    private final EventBus eventBus;

    public DefaultConfiguration(Map<String, String> initialProperties, EventBus eventBus) {
        if (initialProperties != null) {
            properties.putAll(initialProperties);
        }
        this.eventBus = Objects.requireNonNull(eventBus, "EventBus cannot be null");
    }

    @Override
    public String get(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String val = properties.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        String val = properties.get(key);
        return val != null ? Boolean.parseBoolean(val) : defaultValue;
    }

    @Override
    public void set(String key, String value) {
        String previous = properties.put(key, value);
        if (!Objects.equals(previous, value)) {
            eventBus.publish(new ConfigChangedEvent(key, previous, value));
        }
    }

    @Override
    public Set<String> getAllKeys() {
        return Set.copyOf(properties.keySet());
    }
}