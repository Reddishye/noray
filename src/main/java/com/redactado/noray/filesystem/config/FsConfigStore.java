package com.redactado.noray.filesystem.config;

import com.redactado.noray.filesystem.api.FsException;

/** Storage SPI for {@link FsConfig}. */
public interface FsConfigStore {

    /** Loads the stored configuration, or {@link FsConfig#empty()} if nothing was stored yet. */
    FsConfig load() throws FsException;

    /** Durably replaces the stored configuration. Must be atomic: a crash leaves the old or the new one. */
    void save(FsConfig config) throws FsException;

    /** A store that remembers nothing. */
    static FsConfigStore none() {
        return new FsConfigStore() {
            @Override public FsConfig load() { return FsConfig.empty(); }
            @Override public void save(FsConfig config) { }
        };
    }
}
