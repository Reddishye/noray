package com.redactado.noray.filesystem.volume;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.volume.event.VolumeAttached;
import com.redactado.noray.filesystem.volume.event.VolumeDetached;
import com.redactado.noray.filesystem.volume.event.VolumeHealthFailed;
import com.redactado.noray.filesystem.volume.event.VolumeStateChanged;
import com.redactado.noray.kernel.api.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultVolumeManager implements VolumeManager, AutoCloseable {
    private final EventBus eventBus;
    private final DriverRegistry driverRegistry;
    private final Map<VolumeId, SimpleVolume> volumes = new ConcurrentHashMap<>();

    public DefaultVolumeManager(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.driverRegistry = new DriverRegistry();
    }

    public DefaultVolumeManager(EventBus eventBus, DriverRegistry driverRegistry) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
    }

    @Override
    public void registerDriver(VolumeDriver driver) {
        driverRegistry.register(driver);
    }

    @Override
    public synchronized Volume attach(Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        VolumeDriver driver = driverRegistry.findDriver(metadata)
                .orElseThrow(() -> new FsException(FsErrorCode.VOLUME_UNKNOWN, "No suitable volume driver found for metadata"));

        VolumeId volumeId;
        String uuidStr = metadata.getString("uuid", null);
        if (uuidStr != null) {
            try {
                volumeId = new VolumeId(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                volumeId = new VolumeId(UUID.randomUUID());
            }
        } else {
            volumeId = new VolumeId(UUID.randomUUID());
        }

        SimpleVolume existing = volumes.get(volumeId);
        if (existing != null && existing.state() == VolumeState.ONLINE) {
            return existing;
        }

        Volume probed = driver.probe(metadata);
        SimpleVolume volume;
        if (probed instanceof SimpleVolume sv) {
            volume = sv;
        } else {
            volume = new SimpleVolume(probed.id(), probed.kind(), probed.state(), probed.writable(), probed.metadata(), probed.driver(), probed.store());
        }

        volume.setState(VolumeState.PROBING);
        volumes.put(volume.id(), volume);
        eventBus.publish(new VolumeStateChanged(volume.id(), VolumeState.ABSENT, VolumeState.PROBING));

        try {
            driver.attach(volume);
        } catch (Exception e) {
            volume.setState(VolumeState.DETACHED);
            volumes.remove(volume.id());
            eventBus.publish(new VolumeStateChanged(volume.id(), VolumeState.PROBING, VolumeState.DETACHED));
            if (e instanceof FsException fse) {
                throw fse;
            }
            throw new FsException(FsErrorCode.INTERNAL, "Failed to attach volume: " + volume.id(), e);
        }

        volume.setState(VolumeState.ONLINE);
        eventBus.publish(new VolumeStateChanged(volume.id(), VolumeState.PROBING, VolumeState.ONLINE));
        eventBus.publish(new VolumeAttached(volume.id(), volume.kind()));

        return volume;
    }

    @Override
    public synchronized void detach(VolumeId id) {
        Objects.requireNonNull(id, "id");
        SimpleVolume volume = volumes.get(id);
        if (volume == null) {
            throw new FsException(FsErrorCode.VOLUME_UNKNOWN, "Volume not found: " + id);
        }
        if (volume.state() == VolumeState.DETACHED) {
            return;
        }

        VolumeState previousState = volume.state();
        if (previousState != VolumeState.OFFLINE) {
            volume.setState(VolumeState.OFFLINE);
            eventBus.publish(new VolumeStateChanged(id, previousState, VolumeState.OFFLINE));
        }

        try {
            volume.driver().detach(volume);
        } catch (Exception ignored) {
        }

        volume.setState(VolumeState.DETACHED);
        volumes.remove(id);
        eventBus.publish(new VolumeStateChanged(id, VolumeState.OFFLINE, VolumeState.DETACHED));
        eventBus.publish(new VolumeDetached(id));
    }

    @Override
    public Optional<Volume> get(VolumeId id) {
        Objects.requireNonNull(id, "id");
        SimpleVolume volume = volumes.get(id);
        if (volume == null || volume.state() == VolumeState.DETACHED) {
            return Optional.empty();
        }
        return Optional.of(volume);
    }

    @Override
    public List<Volume> list() {
        return volumes.values().stream()
                .filter(v -> v.state() != VolumeState.DETACHED)
                .map(v -> (Volume) v)
                .toList();
    }

    @Override
    public synchronized void markDegraded(VolumeId id, String reason) {
        Objects.requireNonNull(id, "id");
        SimpleVolume volume = volumes.get(id);
        if (volume == null) {
            throw new FsException(FsErrorCode.VOLUME_UNKNOWN, "Volume not found: " + id);
        }
        if (volume.state() == VolumeState.ONLINE) {
            volume.setState(VolumeState.DEGRADED);
            eventBus.publish(new VolumeStateChanged(id, VolumeState.ONLINE, VolumeState.DEGRADED));
            eventBus.publish(new VolumeHealthFailed(id, reason));
        }
    }

    @Override
    public synchronized void markOffline(VolumeId id, String reason) {
        Objects.requireNonNull(id, "id");
        SimpleVolume volume = volumes.get(id);
        if (volume == null) {
            throw new FsException(FsErrorCode.VOLUME_UNKNOWN, "Volume not found: " + id);
        }
        VolumeState current = volume.state();
        if (current == VolumeState.ONLINE || current == VolumeState.DEGRADED) {
            volume.setState(VolumeState.OFFLINE);
            eventBus.publish(new VolumeStateChanged(id, current, VolumeState.OFFLINE));
            eventBus.publish(new VolumeHealthFailed(id, reason));
        }
    }

    @Override
    public synchronized void markOnline(VolumeId id) {
        Objects.requireNonNull(id, "id");
        SimpleVolume volume = volumes.get(id);
        if (volume == null) {
            throw new FsException(FsErrorCode.VOLUME_UNKNOWN, "Volume not found: " + id);
        }
        VolumeState current = volume.state();
        if (current == VolumeState.DEGRADED || current == VolumeState.OFFLINE) {
            volume.setState(VolumeState.ONLINE);
            eventBus.publish(new VolumeStateChanged(id, current, VolumeState.ONLINE));
        }
    }

    @Override
    public synchronized void close() {
        for (VolumeId id : new ArrayList<>(volumes.keySet())) {
            try {
                detach(id);
            } catch (Exception ignored) {
            }
        }
    }
}
