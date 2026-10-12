package com.redactado.noray.filesystem.api;

import java.util.Objects;

public record DirectoryEntry(String name, NodeType type) {
    public DirectoryEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }
}
