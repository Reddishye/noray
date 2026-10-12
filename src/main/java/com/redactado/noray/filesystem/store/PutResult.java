package com.redactado.noray.filesystem.store;

import java.util.Objects;

public record PutResult(ObjectId id, int size, boolean inserted) {
    public PutResult {
        Objects.requireNonNull(id, "id");
    }
}
