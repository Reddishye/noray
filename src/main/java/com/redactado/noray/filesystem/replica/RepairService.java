package com.redactado.noray.filesystem.replica;

import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeManager;
import com.redactado.noray.filesystem.volume.event.VolumeAttached;
import com.redactado.noray.kernel.api.EventBus;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class RepairService implements AutoCloseable {
    private final EventBus eventBus;
    private final VolumeManager volumeManager;
    private final ReplicaPlanner planner;

    public RepairService(EventBus eventBus, VolumeManager volumeManager, ReplicaPlanner planner) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.volumeManager = Objects.requireNonNull(volumeManager, "volumeManager");
        this.planner = Objects.requireNonNull(planner, "planner");

        this.eventBus.subscribe(VolumeAttached.class, this::onVolumeAttached);
    }

    private void onVolumeAttached(VolumeAttached event) {
        Optional<Volume> volOpt = volumeManager.get(event.id());
        if (volOpt.isPresent()) {
            String grpStr = volOpt.get().metadata().getString("replica-group", "");
            if (!grpStr.isBlank()) {
                try {
                    ReplicaGroupId groupId = new ReplicaGroupId(UUID.fromString(grpStr));
                    planner.repair(groupId);
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        eventBus.unsubscribe(VolumeAttached.class, this::onVolumeAttached);
    }
}
