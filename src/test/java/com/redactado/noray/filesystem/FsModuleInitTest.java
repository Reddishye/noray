package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.config.FsConfigService;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.vfs.VirtualFileSystem;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeManager;
import com.redactado.noray.kernel.api.KernelPhase;
import com.redactado.noray.kernel.core.NorayKernel;
import com.redactado.noray.net.NetModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FsModuleInitTest {

    @TempDir
    Path tmp;

    private NorayKernel newKernel() {
        Map<String, String> props = Map.of(
                "noray.data.dir", tmp.resolve("data").toString(),
                "noray.net.udp.port", "0",
                "noray.net.mesh.psk.insecure", "true");
        NorayKernel kernel = new NorayKernel(props);
        kernel.registerModule(new NetModule());
        kernel.registerModule(new FsModule());
        return kernel;
    }

    @Test
    void bootWithFsModuleTransitionsToRunningAndRegistersActiveModule() {
        try (NorayKernel kernel = newKernel()) {
            kernel.boot();

            assertEquals(KernelPhase.RUNNING, kernel.getContext().getPhase());
            assertTrue(kernel.getContext().getActiveModuleNames().contains("noray.fs"));

            kernel.shutdown();
            assertEquals(KernelPhase.TERMINATED, kernel.getContext().getPhase());
        }
    }

    @Test
    void persistentVolumeMountAndNamespaceSurviveRestart() {
        Path volumeDir = tmp.resolve("disk");
        byte[] content = "still here after reboot".getBytes(StandardCharsets.UTF_8);
        UUID uuid;

        try (NorayKernel kernel = newKernel()) {
            kernel.boot();
            var registry = kernel.getContext().getServiceRegistry();
            Volume v = registry.require(FsConfigService.class)
                    .addVolume(Metadata.builder().put("kind", "local").put("path", volumeDir.toString()).build());
            uuid = v.uuid();
            registry.require(VirtualFileSystem.class)
                    .mount(new Mount("data", VfsPath.parse("/mnt/data"), v.id(), ""));

            FileSystemService fs = registry.require(FileSystemService.class);
            fs.mkdir(VfsPath.parse("/mnt/data/docs"));
            fs.createFile(VfsPath.parse("/mnt/data/docs/a.txt"));
            fs.writeFully(VfsPath.parse("/mnt/data/docs/a.txt"), content);
            kernel.shutdown();
        }

        // Nothing is mounted or attached by hand now: everything must come back on its own.
        try (NorayKernel kernel = newKernel()) {
            kernel.boot();
            var registry = kernel.getContext().getServiceRegistry();
            assertTrue(registry.require(VolumeManager.class).list().stream().anyMatch(v -> v.uuid().equals(uuid)));
            assertEquals(1, registry.require(VirtualFileSystem.class).mounts().size());

            FileSystemService fs = registry.require(FileSystemService.class);
            assertEquals(NodeType.DIRECTORY, fs.stat(VfsPath.parse("/mnt/data/docs")).type());
            assertArrayEquals(content, fs.readFully(VfsPath.parse("/mnt/data/docs/a.txt")));
            kernel.shutdown();
        }
    }

    @Test
    void volatileVolumesAreNotRestoredAndLeaveNoGhostFiles() {
        try (NorayKernel kernel = newKernel()) {
            kernel.boot();
            var registry = kernel.getContext().getServiceRegistry();
            Volume v = registry.require(VolumeManager.class)
                    .attach(Metadata.builder().put("kind", "memory").build());
            registry.require(VirtualFileSystem.class)
                    .mount(new Mount("tmp", VfsPath.parse("/mnt/tmp"), v.id(), ""));
            registry.require(FileSystemService.class).mkdir(VfsPath.parse("/mnt/tmp/ghost"));
            kernel.shutdown();
        }

        try (NorayKernel kernel = newKernel()) {
            kernel.boot();
            var registry = kernel.getContext().getServiceRegistry();
            assertTrue(registry.require(VolumeManager.class).list().isEmpty());
            assertTrue(registry.require(VirtualFileSystem.class).mounts().isEmpty());

            // a fresh in-memory volume at the same place must start empty
            Volume v = registry.require(VolumeManager.class)
                    .attach(Metadata.builder().put("kind", "memory").build());
            registry.require(VirtualFileSystem.class)
                    .mount(new Mount("tmp", VfsPath.parse("/mnt/tmp"), v.id(), ""));
            FileSystemService fs = registry.require(FileSystemService.class);
            FsException ex = assertThrows(FsException.class, () -> fs.stat(VfsPath.parse("/mnt/tmp/ghost")));
            assertEquals(FsErrorCode.NOT_FOUND, ex.code());
            kernel.shutdown();
        }
    }

    @Test
    void removedVolumeIsNotRestored() {
        UUID uuid;
        try (NorayKernel kernel = newKernel()) {
            kernel.boot();
            FsConfigService cfg = kernel.getContext().getServiceRegistry().require(FsConfigService.class);
            uuid = cfg.addVolume(Metadata.builder().put("kind", "local")
                    .put("path", tmp.resolve("disk2").toString()).build()).uuid();
            cfg.removeVolume(new com.redactado.noray.filesystem.volume.VolumeId(uuid));
            kernel.shutdown();
        }
        try (NorayKernel kernel = newKernel()) {
            kernel.boot();
            assertTrue(kernel.getContext().getServiceRegistry().require(VolumeManager.class).list().isEmpty());
            kernel.shutdown();
        }
    }
}
