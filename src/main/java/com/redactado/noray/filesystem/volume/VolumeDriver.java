package com.redactado.noray.filesystem.volume;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.api.FsException;

public interface VolumeDriver {
    String name();                 // "memory", "local", ...
    VolumeKind kind();
    boolean supports(Metadata metadata);
    Volume probe(Metadata metadata) throws FsException;   // state PROBING→ONLINE internally via manager
    void attach(Volume volume) throws FsException;
    void detach(Volume volume) throws FsException;
    boolean isHealthy(Volume volume);
}
