package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.DirectoryEntry;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.volume.VolumeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VirtualFileSystemTest {
    private DefaultVirtualFileSystem vfs;

    @BeforeEach
    void setUp() {
        vfs = new DefaultVirtualFileSystem();
    }

    @Test
    void vfsPathNormalizationAndValidation() {
        VfsPath p = VfsPath.parse("//a/b/");
        assertEquals("/a/b", p.toString());
        assertEquals(List.of("a", "b"), p.segments());
        assertEquals("b", p.name());
        assertEquals("/a", p.parent().toString());
        assertEquals("/", p.parent().parent().toString());
        assertNull(VfsPath.root().parent());

        // Rejections
        FsException ex1 = assertThrows(FsException.class, () -> VfsPath.parse(""));
        assertEquals(FsErrorCode.INVALID_PATH, ex1.code());

        FsException ex2 = assertThrows(FsException.class, () -> VfsPath.parse(null));
        assertEquals(FsErrorCode.INVALID_PATH, ex2.code());

        FsException ex3 = assertThrows(FsException.class, () -> VfsPath.parse("a/b"));
        assertEquals(FsErrorCode.INVALID_PATH, ex3.code());

        FsException ex4 = assertThrows(FsException.class, () -> VfsPath.parse("/a/\0/b"));
        assertEquals(FsErrorCode.INVALID_PATH, ex4.code());

        FsException ex5 = assertThrows(FsException.class, () -> VfsPath.parse("/a/./b"));
        assertEquals(FsErrorCode.INVALID_PATH, ex5.code());

        FsException ex6 = assertThrows(FsException.class, () -> VfsPath.parse("/a/../b"));
        assertEquals(FsErrorCode.INVALID_PATH, ex6.code());
    }

    @Test
    void longestPrefixMountResolution() {
        VolumeId volRoot = new VolumeId(UUID.randomUUID());
        VolumeId volMnt = new VolumeId(UUID.randomUUID());
        VolumeId volData = new VolumeId(UUID.randomUUID());

        vfs.mount(new Mount("root", VfsPath.root(), volRoot, ""));
        vfs.mount(new Mount("mnt", VfsPath.parse("/mnt"), volMnt, ""));
        vfs.mount(new Mount("data", VfsPath.parse("/mnt/data"), volData, ""));

        Optional<Mount> m1 = vfs.findMount(VfsPath.parse("/mnt/data/files/test.txt"));
        assertTrue(m1.isPresent());
        assertEquals("data", m1.get().name());

        Optional<Mount> m2 = vfs.findMount(VfsPath.parse("/mnt/other/test.txt"));
        assertTrue(m2.isPresent());
        assertEquals("mnt", m2.get().name());

        Optional<Mount> m3 = vfs.findMount(VfsPath.parse("/vol/123"));
        assertTrue(m3.isPresent());
        assertEquals("root", m3.get().name());
    }

    @Test
    void cannotMountTwiceOnSamePoint() {
        VolumeId vol = new VolumeId(UUID.randomUUID());
        vfs.mount(new Mount("mnt1", VfsPath.parse("/mnt/data"), vol, ""));

        FsException ex = assertThrows(FsException.class, () ->
                vfs.mount(new Mount("mnt2", VfsPath.parse("/mnt/data"), vol, "")));
        assertEquals(FsErrorCode.ALREADY_EXISTS, ex.code());
    }

    @Test
    void unmountMissingThrowsNotFound() {
        FsException ex = assertThrows(FsException.class, () ->
                vfs.unmount(VfsPath.parse("/mnt/nonexistent")));
        assertEquals(FsErrorCode.NOT_FOUND, ex.code());
    }

    @Test
    void unmountRemovesFromTable() {
        VolumeId vol = new VolumeId(UUID.randomUUID());
        VfsPath path = VfsPath.parse("/mnt/data");
        vfs.mount(new Mount("data", path, vol, ""));

        assertEquals(1, vfs.mounts().size());
        vfs.unmount(path);
        assertEquals(0, vfs.mounts().size());
        assertTrue(vfs.findMount(path).isEmpty());
    }

    @Test
    void listRootAndSyntheticDirs() {
        List<DirectoryEntry> rootEntries = vfs.list(VfsPath.root());
        List<String> names = rootEntries.stream().map(DirectoryEntry::name).sorted().toList();
        assertEquals(List.of("mnt", "repo", "vol"), names);
        assertTrue(rootEntries.stream().allMatch(e -> e.type() == NodeType.DIRECTORY));

        // Mount under /mnt and verify list("/mnt")
        VolumeId vol = new VolumeId(UUID.randomUUID());
        vfs.mount(new Mount("data", VfsPath.parse("/mnt/data"), vol, ""));

        List<DirectoryEntry> mntEntries = vfs.list(VfsPath.parse("/mnt"));
        assertEquals(1, mntEntries.size());
        assertEquals("data", mntEntries.get(0).name());
        assertEquals(NodeType.DIRECTORY, mntEntries.get(0).type());

        // Non-synthetic root child
        assertThrows(FsException.class, () -> vfs.list(VfsPath.parse("/other")));
    }
}
