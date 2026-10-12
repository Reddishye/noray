package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.meta.StringMetadataItem;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable metadata bag. Empty-safe. Thread-safe. */
public final class Metadata {
    private static final Metadata EMPTY = new Metadata(Collections.emptyMap());

    private final Map<String, MetadataItem> items;

    private Metadata(Map<String, MetadataItem> items) {
        this.items = Collections.unmodifiableMap(new HashMap<>(items));
    }

    public static Metadata empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<MetadataItem> get(String key) {
        return Optional.ofNullable(items.get(key));
    }

    public String getString(String key, String defaultValue) {
        MetadataItem item = items.get(key);
        if (item != null && item.value() != null) {
            return item.value();
        }
        return defaultValue;
    }

    public Map<String, MetadataItem> asMap() {
        return items;
    }

    public static final class Builder {
        private final Map<String, MetadataItem> map = new HashMap<>();

        public Builder put(String key, String value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            map.put(key, new StringMetadataItem(key, value));
            return this;
        }

        public Builder put(MetadataItem item) {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(item.key(), "item.key()");
            map.put(item.key(), item);
            return this;
        }

        public Metadata build() {
            if (map.isEmpty()) {
                return EMPTY;
            }
            return new Metadata(map);
        }
    }
}
