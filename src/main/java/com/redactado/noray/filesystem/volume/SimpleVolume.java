package com.redactado.noray.filesystem.volume;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectStore;

import java.util.Objects;
import java.util.UUID;

public class SimpleVolume implements Volume {
    private final VolumeId id;
    private final VolumeKind kind;
    private final boolean writable;
    private final Metadata metadata;
    private final VolumeDriver driver;
    private final ObjectStore store;
    private volatile VolumeState state;

    public SimpleVolume(VolumeId id, VolumeKind kind, VolumeState state, boolean writable, Metadata metadata, VolumeDriver driver) {
        this(id, kind, state, writable, metadata, driver, new InMemoryObjectStore());
    }

    public SimpleVolume(VolumeId id, VolumeKind kind, VolumeState state, boolean writable, Metadata metadata, VolumeDriver driver, ObjectStore store) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.state = Objects.requireNonNull(state, "state");
        this.writable = writable;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public VolumeId id() {
        return id;
    }

    @Override
    public UUID uuid() {
        return id.value();
    }

    @Override
    public VolumeKind kind() {
        return kind;
    }

    @Override
    public VolumeState state() {
        return state;
    }

    public void setState(VolumeState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public boolean writable() {
        return writable;
    }

    @Override
    public VolumeDriver driver() {
        return driver;
    }

    @Override
    public Metadata metadata() {
        return metadata;
    }

    @Override
    public ObjectStore store() {
        return store;
    }
}
