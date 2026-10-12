package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.*;
import com.redactado.noray.filesystem.store.ContentDigest;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.vfs.DefaultFileSystemService;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.volume.DefaultVolumeManager;
import com.redactado.noray.filesystem.volume.InMemoryVolumeDriver;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemServiceTest {
    private ExecutorService executor;
    private ConcurrentEventBus eventBus;
    private DefaultVolumeManager volumeManager;
    private DefaultVirtualFileSystem vfs;
    private InMemoryObjectStore store;
    private DefaultFileSystemService fsService;
    private Volume mountedVolume;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        eventBus = new ConcurrentEventBus(executor, (t, ctx) -> {});
        volumeManager = new DefaultVolumeManager(eventBus);
        volumeManager.registerDriver(new InMemoryVolumeDriver());
        vfs = new DefaultVirtualFileSystem();
        store = new InMemoryObjectStore();
        fsService = new DefaultFileSystemService(vfs, volumeManager, store);

        Metadata metadata = Metadata.builder().put("kind", "memory").build();
        mountedVolume = volumeManager.attach(metadata);
        vfs.mount(new Mount("data", VfsPath.parse("/mnt/data"), mountedVolume.id(), ""));
    }

    @AfterEach
    void tearDown() {
        volumeManager.close();
        executor.shutdownNow();
    }

    @Test
    void mkdirCreateFileWriteFullyReadFullyRoundtrip() {
        VfsPath dir = VfsPath.parse("/mnt/data/docs");
        VfsPath file = VfsPath.parse("/mnt/data/docs/hello.txt");
        byte[] content = "Hello Noray VFS!".getBytes(StandardCharsets.UTF_8);

        fsService.mkdir(dir);
        DirectoryEntry dirStat = fsService.stat(dir);
        assertEquals("docs", dirStat.name());
        assertEquals(NodeType.DIRECTORY, dirStat.type());

        fsService.createFile(file);
        DirectoryEntry fileStat = fsService.stat(file);
        assertEquals("hello.txt", fileStat.name());
        assertEquals(NodeType.FILE, fileStat.type());

        ObjectId objectId = fsService.writeFully(file, content);
        assertEquals(ContentDigest.sha256(content), objectId);
        assertTrue(mountedVolume.store().has(objectId));

        byte[] readBack = fsService.readFully(file);
        assertArrayEquals(content, readBack);

        List<DirectoryEntry> entries = fsService.readdir(dir);
        assertEquals(1, entries.size());
        assertEquals("hello.txt", entries.get(0).name());
        assertEquals(NodeType.FILE, entries.get(0).type());
    }

    @Test
    void writeFullyOnMissingFileThrowsNotFound() {
        VfsPath missing = VfsPath.parse("/mnt/data/nonexistent.txt");
        byte[] content = "test".getBytes(StandardCharsets.UTF_8);

        FsException ex = assertThrows(FsException.class, () -> fsService.writeFully(missing, content));
        assertEquals(FsErrorCode.NOT_FOUND, ex.code());
    }

    @Test
    void volumeDetachThenReadFullyThrowsVolumeUnavailable() {
        VfsPath file = VfsPath.parse("/mnt/data/test.txt");
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);

        fsService.createFile(file);
        fsService.writeFully(file, content);

        volumeManager.detach(mountedVolume.id());

        FsException ex = assertThrows(FsException.class, () -> fsService.readFully(file));
        assertEquals(FsErrorCode.VOLUME_UNAVAILABLE, ex.code());
    }

    @Test
    void mkdirUnderVolOrRepoThrowsDenied() {
        VfsPath volPath = VfsPath.parse("/vol/x");
        FsException ex1 = assertThrows(FsException.class, () -> fsService.mkdir(volPath));
        assertEquals(FsErrorCode.DENIED, ex1.code());

        VfsPath repoPath = VfsPath.parse("/repo/y");
        FsException ex2 = assertThrows(FsException.class, () -> fsService.createFile(repoPath));
        assertEquals(FsErrorCode.DENIED, ex2.code());
    }

    @Test
    void writeFullyOnDirectoryThrowsIsDirectory() {
        VfsPath dir = VfsPath.parse("/mnt/data/mydir");
        fsService.mkdir(dir);

        FsException ex = assertThrows(FsException.class, () ->
                fsService.writeFully(dir, "content".getBytes(StandardCharsets.UTF_8)));
        assertEquals(FsErrorCode.IS_DIRECTORY, ex.code());
    }
}
