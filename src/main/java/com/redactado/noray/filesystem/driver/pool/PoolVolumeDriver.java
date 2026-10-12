package com.redactado.noray.filesystem.driver.pool;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.MetadataItem;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.store.PutResult;
import com.redactado.noray.filesystem.volume.SimpleVolume;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeDriver;
import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.filesystem.volume.VolumeKind;
import com.redactado.noray.filesystem.volume.VolumeState;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A <em>pool</em> is a mount whose files are stored by the data engine across all engine nodes instead of in one
 * volume ({@code kind=pool}, optional {@code policy=<name>} as the default storage policy of the mount).
 * The pool volume itself holds no blobs.
 */
public final class PoolVolumeDriver implements VolumeDriver {

    @Override public String name() { return "pool"; }

    @Override public VolumeKind kind() { return VolumeKind.POOL; }

    @Override
    public boolean supports(Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return "pool".equalsIgnoreCase(metadata.getString("kind", ""));
    }

    @Override
    public Volume probe(Metadata metadata) throws FsException {
        UUID uuid;
        try {
            String raw = metadata.getString("uuid", null);
            uuid = raw != null ? UUID.fromString(raw) : UUID.randomUUID();
        } catch (IllegalArgumentException e) {
            uuid = UUID.randomUUID();
        }
        Metadata.Builder resolved = Metadata.builder();
        for (MetadataItem item : metadata.asMap().values()) {
            resolved.put(item);
        }
        resolved.put("uuid", uuid.toString());
        return new SimpleVolume(new VolumeId(uuid), VolumeKind.POOL, VolumeState.PROBING, false,
                resolved.build(), this, new NoStore());
    }

    @Override public void attach(Volume volume) { }

    @Override public void detach(Volume volume) { }

    @Override public boolean isHealthy(Volume volume) { return true; }

    /** A pool keeps nothing itself; its files live on engine nodes. */
    private static final class NoStore implements ObjectStore {
        private static FsException unsupported() {
            return new FsException(FsErrorCode.READ_ONLY, "a pool volume stores files through the data engine only");
        }

        @Override public PutResult put(byte[] data) { throw unsupported(); }
        @Override public byte[] get(ObjectId id) { throw unsupported(); }
        @Override public boolean has(ObjectId id) { return false; }
        @Override public void delete(ObjectId id) { throw unsupported(); }
        @Override public long size(ObjectId id) { throw unsupported(); }
        @Override public Set<ObjectId> listIds() { return Set.of(); }
    }
}
