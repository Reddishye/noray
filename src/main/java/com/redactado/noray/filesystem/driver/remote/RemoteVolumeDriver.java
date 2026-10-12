package com.redactado.noray.filesystem.driver.remote;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.remote.RemoteObjectStore;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeDriver;
import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.filesystem.volume.VolumeKind;
import com.redactado.noray.filesystem.volume.VolumeState;
import com.redactado.noray.filesystem.volume.SimpleVolume;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class RemoteVolumeDriver implements VolumeDriver {

    private static final Pattern URI_PATTERN = Pattern.compile("^noray://([0-9a-f]{64})$");

    private final MeshTransport transport;

    public RemoteVolumeDriver(MeshTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public String name() {
        return "remote";
    }

    @Override
    public VolumeKind kind() {
        return VolumeKind.REMOTE;
    }

    @Override
    public boolean supports(Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        String kind = metadata.getString("kind", "");
        return "remote".equalsIgnoreCase(kind);
    }

    @Override
    public Volume probe(Metadata metadata) throws FsException {
        Objects.requireNonNull(metadata, "metadata");
        String uri = metadata.getString("uri", "");
        if (uri.isEmpty()) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "Remote volume requires uri");
        }
        var matcher = URI_PATTERN.matcher(uri);
        if (!matcher.matches()) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "Invalid remote uri: " + uri);
        }
        NodeId peer = new NodeId(matcher.group(1));
        if (!transport.isEnrolled(peer)) {
            throw new FsException(FsErrorCode.DENIED, "Peer not enrolled: " + peer);
        }
        UUID uuid;
        String uuidStr = metadata.getString("uuid", null);
        if (uuidStr != null) {
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                uuid = UUID.randomUUID();
            }
        } else {
            uuid = UUID.randomUUID();
        }
        boolean writable = Boolean.parseBoolean(metadata.getString("writable", "true"));
        RemoteObjectStore store = new RemoteObjectStore(transport, peer);
        return new SimpleVolume(new VolumeId(uuid), VolumeKind.REMOTE, VolumeState.PROBING, writable, metadata, this, store);
    }

    @Override
    public void attach(Volume volume) throws FsException {
    }

    @Override
    public void detach(Volume volume) throws FsException {
        if (volume != null && volume.store() instanceof RemoteObjectStore remote) {
            remote.close();
        }
    }

    @Override
    public boolean isHealthy(Volume volume) {
        if (volume != null && volume.store() instanceof RemoteObjectStore remote) {
            return remote.alive(PROBE_MILLIS);
        }
        return true;
    }

    private static final long PROBE_MILLIS = 4_000;
}