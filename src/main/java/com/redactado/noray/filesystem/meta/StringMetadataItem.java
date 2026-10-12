package com.redactado.noray.filesystem.meta;

import com.redactado.noray.filesystem.MetadataItem;

import java.util.Objects;

public record StringMetadataItem(String key, String value) implements MetadataItem {
    public StringMetadataItem {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }

    @Override
    public Class<?> type() {
        return String.class;
    }
}
