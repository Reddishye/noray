package com.redactado.noray.data.placement;

import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeKind;
import com.redactado.noray.filesystem.volume.VolumeManager;
import com.redactado.noray.filesystem.volume.VolumeState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Exposes the volumes of a {@link VolumeManager} that opted in with {@code engine=true} (and are writable) as
 * storage nodes. The opt-in matters: the engine garbage-collects blobs it does not know about on its nodes, so
 * a volume that also holds other data (legacy mounts, blobs of remote peers) must never be an engine node.
 * A volume is configured with its metadata: {@code weight=<number>} capacity weight (default 1), {@code speed=<number>} speed weight
 * (default 1), {@code accepts=hot,cold} classes of data it may hold (default both) and
 * {@code domain=<name>} (default: its own uuid, i.e. every volume is its own failure domain).
 */
public final class VolumeCluster implements DataCluster {

    private final VolumeManager volumes;

    public VolumeCluster(VolumeManager volumes) {
        this.volumes = Objects.requireNonNull(volumes, "volumes");
    }

    @Override
    public List<StorageNode> nodes() {
        List<StorageNode> out = new ArrayList<>();
        for (Volume v : volumes.list()) {
            if (v.writable() && v.kind() != VolumeKind.POOL
                    && Boolean.parseBoolean(v.metadata().getString("engine", "false"))) {
                out.add(new VolumeNode(v));
            }
        }
        return out;
    }

    private static final class VolumeNode implements StorageNode {
        private final Volume volume;

        VolumeNode(Volume volume) {
            this.volume = volume;
        }

        @Override public String id() { return volume.uuid().toString(); }

        @Override
        public double capacityWeight() {
            return number("weight");
        }

        @Override
        public double speedWeight() {
            return number("speed");
        }

        @Override
        public boolean accepts(Tier tier) {
            String list = volume.metadata().getString("accepts", "hot,cold").toLowerCase(Locale.ROOT);
            for (String item : list.split(",")) {
                if (item.trim().equals(tier.name().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        private double number(String key) {
            try {
                double w = Double.parseDouble(volume.metadata().getString(key, "1"));
                return w > 0 ? w : 1.0;
            } catch (NumberFormatException e) {
                return 1.0;
            }
        }

        @Override
        public String domain() {
            return volume.metadata().getString("domain", volume.uuid().toString());
        }

        @Override
        public boolean available() {
            VolumeState s = volume.state();
            return s == VolumeState.ONLINE || s == VolumeState.DEGRADED;
        }

        @Override public ObjectStore store() { return volume.store(); }
    }
}
