package com.redactado.noray.repo;

import com.redactado.noray.filesystem.store.ObjectId;
import java.util.Objects;

public record RefName(String value) {
    public RefName {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Za-z0-9._/-]+") || value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            throw new IllegalArgumentException("Invalid ref name: " + value);
        }
    }
}
