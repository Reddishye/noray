package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.config.FileFsConfigStore;
import com.redactado.noray.filesystem.config.FsConfig;
import com.redactado.noray.filesystem.config.FsConfigService;
import com.redactado.noray.filesystem.config.FsConfigStore;
import com.redactado.noray.filesystem.driver.local.LocalVolumeDriver;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.volume.DefaultVolumeManager;
import com.redactado.noray.filesystem.volume.InMemoryVolumeDriver;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class FsConfigTest {

    @TempDir
    Path tmp;

    private ExecutorService executor;
    private Path configFile;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        configFile = tmp.resolve("cfg").resolve("volumes.properties");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** One simulated boot: fresh managers, same config file. */
    private final class Node implements AutoCloseable {
        final DefaultVolumeManager volumes = new DefaultVolumeManager(new ConcurrentEventBus(executor, (t, c) -> {}));
        final DefaultVirtualFileSystem vfs = new DefaultVirtualFileSystem();
        final FsConfigService config;
        final FsConfigService.RestoreReport report;

        Node(FsConfigStore store) {
            volumes.registerDriver(new InMemoryVolumeDriver());
            volumes.registerDriver(new LocalVolumeDriver());
            config = new FsConfigService(volumes, vfs, store);
            report = config.restore();
        }

        Node() {
            this(new FileFsConfigStore(configFile));
        }

        @Override
        public void close() {
            volumes.close(); // detaching on shutdown must never edit the stored config
        }
    }

    private static Metadata local(Path dir) {
        return Metadata.builder().put("kind", "local").put("path", dir.toString()).build();
    }

    @Test
    void volumesAndMountsComeBackAfterRestart() {
        Path disk = tmp.resolve("disk");
        VolumeId id;
        try (Node n = new Node()) {
            Volume v = n.config.addVolume(local(disk));
            id = v.id();
            n.vfs.mount(new Mount("data", VfsPath.parse("/mnt/data"), id, "ro"));
        }
        try (Node n = new Node()) {
            assertEquals(List.of(id), n.report.attached());
            assertTrue(n.report.failedVolumes().isEmpty());
            assertTrue(n.volumes.get(id).isPresent());
            assertEquals(1, n.vfs.mounts().size());
            Mount m = n.vfs.mounts().get(0);
            assertEquals("data", m.name());
            assertEquals(VfsPath.parse("/mnt/data"), m.mountPoint());
            assertEquals(id, m.volumeId());
            assertEquals("ro", m.options());
        }
    }

    @Test
    void unmountIsPersisted() {
        try (Node n = new Node()) {
            Volume v = n.config.addVolume(local(tmp.resolve("disk")));
            n.vfs.mount(new Mount("a", VfsPath.parse("/mnt/a"), v.id(), ""));
            n.vfs.mount(new Mount("b", VfsPath.parse("/mnt/b"), v.id(), ""));
            n.vfs.unmount(VfsPath.parse("/mnt/a"));
        }
        try (Node n = new Node()) {
            assertEquals(List.of(VfsPath.parse("/mnt/b")),
                    n.vfs.mounts().stream().map(Mount::mountPoint).toList());
        }
    }

    @Test
    void mountMadeBeforeTheVolumeWasDefinedIsPersistedOnceItIs() {
        VolumeId id = new VolumeId(UUID.randomUUID());
        try (Node n = new Node()) {
            n.vfs.mount(new Mount("early", VfsPath.parse("/mnt/early"), id, ""));
            n.config.addVolume(Metadata.builder().put("kind", "local")
                    .put("path", tmp.resolve("disk").toString()).put("uuid", id.value().toString()).build());
        }
        try (Node n = new Node()) {
            assertEquals(1, n.vfs.mounts().size());
        }
    }

    @Test
    void hotPluggedAndMemoryVolumesAreNotPersisted() {
        try (Node n = new Node()) {
            Volume mem = n.volumes.attach(Metadata.builder().put("kind", "memory").build());
            n.vfs.mount(new Mount("mem", VfsPath.parse("/mnt/mem"), mem.id(), ""));
        }
        try (Node n = new Node()) {
            assertTrue(n.volumes.list().isEmpty());
            assertTrue(n.vfs.mounts().isEmpty());
        }
    }

    @Test
    void removedVolumeIsForgotten() {
        VolumeId id;
        try (Node n = new Node()) {
            id = n.config.addVolume(local(tmp.resolve("disk"))).id();
            n.config.removeVolume(id);
            assertTrue(n.volumes.get(id).isEmpty());
        }
        try (Node n = new Node()) {
            assertTrue(n.report.attached().isEmpty());
            assertTrue(n.config.definitions().isEmpty());
        }
    }

    @Test
    void removingUnknownVolumeFails() {
        try (Node n = new Node()) {
            FsException e = assertThrows(FsException.class,
                    () -> n.config.removeVolume(new VolumeId(UUID.randomUUID())));
            assertEquals(FsErrorCode.VOLUME_UNKNOWN, e.code());
        }
    }

    @Test
    void unavailableVolumeDoesNotBlockOthersAndStaysDefined() throws IOException {
        Path good = tmp.resolve("good");
        Path blocked = tmp.resolve("blocked");
        VolumeId goodId;
        try (Node n = new Node()) {
            goodId = n.config.addVolume(local(good)).id();
            n.config.addVolume(local(blocked));
        }
        // the second "disk" becomes unusable: a regular file where the directory should be
        deleteRecursively(blocked);
        Files.writeString(blocked, "not a directory");

        try (Node n = new Node()) {
            assertEquals(List.of(goodId), n.report.attached());
            assertEquals(1, n.report.failedVolumes().size());
            assertEquals(2, n.config.definitions().size(), "failed volume stays defined for the next boot");
        }

        Files.delete(blocked);
        try (Node n = new Node()) {
            assertEquals(2, n.report.attached().size());
        }
    }

    @Test
    void aVolumeThatWasNotThereAtBootIsAttachedLater() throws IOException {
        Path late = tmp.resolve("late");
        VolumeId id;
        try (Node n = new Node()) {
            id = n.config.addVolume(local(late)).id();
            n.vfs.mount(new Mount("late", VfsPath.parse("/mnt/late"), id, ""));
        }
        deleteRecursively(late);
        Files.writeString(late, "not a directory yet");

        try (Node n = new Node()) {
            assertEquals(1, n.report.failedVolumes().size());
            FsConfigService.RetryReport still = n.config.retryPending();
            assertTrue(still.attached().isEmpty());
            assertEquals(1, still.stillPending());

            Files.delete(late); // the disk shows up
            FsConfigService.RetryReport r = n.config.retryPending();
            assertEquals(List.of(id), r.attached());
            assertEquals(0, r.stillPending());
            assertTrue(n.volumes.get(id).isPresent());
            assertEquals(1, n.vfs.mounts().size());
        }
    }

    @Test
    void aVolumeWaitingToBeAttachedSurvivesOtherConfigurationChanges() throws IOException {
        Path late = tmp.resolve("late");
        VolumeId id;
        try (Node n = new Node()) {
            id = n.config.addVolume(local(late)).id();
            n.vfs.mount(new Mount("late", VfsPath.parse("/mnt/late"), id, ""));
        }
        deleteRecursively(late);
        Files.writeString(late, "x");
        try (Node n = new Node()) {
            n.config.addVolume(local(tmp.resolve("other"))); // rewrites the file while the mount is still waiting
        }
        Files.delete(late);
        try (Node n = new Node()) {
            assertEquals(1, n.report.mounted().size(), "the mount was not lost from the stored configuration");
        }
    }

    @Test
    void failedPersistRollsBackVolumeAndMount() {
        FsConfigStore failing = new FsConfigStore() {
            @Override public FsConfig load() { return FsConfig.empty(); }
            @Override public void save(FsConfig config) { throw new FsException(FsErrorCode.INTERNAL, "disk full"); }
        };
        try (Node n = new Node(failing)) {
            assertThrows(FsException.class, () -> n.config.addVolume(local(tmp.resolve("disk"))));
            assertTrue(n.volumes.list().isEmpty());
            assertTrue(n.config.definitions().isEmpty());

            Volume mem = n.volumes.attach(Metadata.builder().put("kind", "memory").build());
            assertThrows(FsException.class,
                    () -> n.vfs.mount(new Mount("m", VfsPath.parse("/mnt/m"), mem.id(), "")));
            assertTrue(n.vfs.mounts().isEmpty(), "mount must be rolled back when it cannot be persisted");
        }
    }

    @Test
    void configFileIsOwnerOnlyAndReadable() throws IOException {
        try (Node n = new Node()) {
            n.config.addVolume(local(tmp.resolve("disk")));
        }
        assertTrue(Files.isRegularFile(configFile));
        try {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(configFile));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
        FsConfig loaded = new FileFsConfigStore(configFile).load();
        assertEquals(1, loaded.volumes().size());
    }

    @Test
    void storeRoundTripsAwkwardValues() {
        FileFsConfigStore store = new FileFsConfigStore(configFile);
        VolumeId id = new VolumeId(UUID.randomUUID());
        Metadata meta = Metadata.builder()
                .put("kind", "local")
                .put("path", "/tmp/with space/and=equals:colon\\backslash")
                .put("note", "line1\nline2 ünïcödé")
                .put("uuid", id.value().toString())
                .build();
        Mount mount = new Mount("n=1", VfsPath.parse("/mnt/a b"), id, "k=v");
        store.save(new FsConfig(Map.of(id, meta), List.of(mount)));

        FsConfig loaded = store.load();
        Metadata back = loaded.volumes().get(id);
        assertEquals("/tmp/with space/and=equals:colon\\backslash", back.getString("path", null));
        assertEquals("line1\nline2 ünïcödé", back.getString("note", null));
        assertEquals(List.of(mount), loaded.mounts());
    }

    @Test
    void corruptConfigFailsLoudlyInsteadOfBeingOverwritten() throws IOException {
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "volume.not-a-uuid.kind=local\n");
        FsException e = assertThrows(FsException.class, () -> new Node());
        assertEquals(FsErrorCode.INTERNAL, e.code());
        assertEquals("volume.not-a-uuid.kind=local\n", Files.readString(configFile));
    }

    @Test
    void localVolumeStoresBlobsDurably() {
        Path disk = tmp.resolve("disk");
        byte[] data = "blob".getBytes();
        com.redactado.noray.filesystem.store.ObjectId id;
        try (Node n = new Node()) {
            Volume v = n.config.addVolume(local(disk));
            id = v.store().put(data).id();
        }
        try (Node n = new Node()) {
            Volume v = n.volumes.list().get(0);
            assertArrayEquals(data, v.store().get(id));
        }
    }

    @Test
    void localVolumeRequiresPath() {
        try (Node n = new Node()) {
            assertThrows(FsException.class,
                    () -> n.config.addVolume(Metadata.builder().put("kind", "local").build()));
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }
}
