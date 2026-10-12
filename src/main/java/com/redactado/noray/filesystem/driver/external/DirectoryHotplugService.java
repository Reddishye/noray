package com.redactado.noray.filesystem.driver.external;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.filesystem.volume.VolumeManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DirectoryHotplugService implements AutoCloseable {

    private final VolumeManager manager;
    private final Path dir;
    private final UUID uuid;
    private final Duration interval;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread thread;
    private volatile VolumeId attachedId;

    public DirectoryHotplugService(VolumeManager manager, Path dir, UUID uuid, Duration interval) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.dir = Objects.requireNonNull(dir, "dir");
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.thread = new Thread(this::run, "noray-fs-hotplug");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    private void run() {
        while (!closed.get()) {
            boolean exists = Files.isDirectory(dir);
            VolumeId current = attachedId;
            if (exists && current == null) {
                Metadata metadata = Metadata.builder()
                        .put("kind", "external")
                        .put("uuid", uuid.toString())
                        .put("uri", dir.toUri().toString())
                        .put("writable", "true")
                        .build();
                Volume volume = manager.attach(metadata);
                attachedId = volume.id();
            } else if (!exists && current != null) {
                try {
                    manager.detach(current);
                } catch (FsException ignored) {
                }
                attachedId = null;
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                if (closed.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        thread.interrupt();
    }
}