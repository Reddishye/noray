package com.redactado.noray.filesystem.volume;

import com.redactado.noray.filesystem.Metadata;

import java.util.List;
import java.util.Optional;

public interface VolumeManager {
    void registerDriver(VolumeDriver driver);
    Volume attach(Metadata metadata);      // choose driver via supports(); emit events
    void detach(VolumeId id);              // ONLINE/DEGRADED → OFFLINE → DETACHED
    Optional<Volume> get(VolumeId id);
    List<Volume> list();
    void markDegraded(VolumeId id, String reason);
    void markOffline(VolumeId id, String reason);
    /** DEGRADED/OFFLINE → ONLINE again (the health check succeeded). */
    void markOnline(VolumeId id);
}
