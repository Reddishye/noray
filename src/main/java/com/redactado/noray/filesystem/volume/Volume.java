package com.redactado.noray.filesystem.volume;

import com.redactado.noray.filesystem.Partition;
import com.redactado.noray.filesystem.store.ObjectStore;

public interface Volume extends Partition {
    VolumeId id();
    VolumeKind kind();
    VolumeState state();
    boolean writable();
    VolumeDriver driver();
    ObjectStore store();
}
