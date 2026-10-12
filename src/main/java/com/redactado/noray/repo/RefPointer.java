package com.redactado.noray.repo;

import com.redactado.noray.filesystem.store.ObjectId;
import java.util.Objects;

public record RefPointer(RefName name, ObjectId target, long generation) {
    public RefPointer {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(target, "target");
        if (generation < 0) throw new IllegalArgumentException("generation");
    }
}
