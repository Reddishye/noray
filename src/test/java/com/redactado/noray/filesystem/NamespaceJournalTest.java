package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.DirectoryEntry;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.vfs.DefaultFileSystemService;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.vfs.journal.FileNamespaceJournal;
import com.redactado.noray.filesystem.vfs.journal.NamespaceJournal;
import com.redactado.noray.filesystem.vfs.journal.NamespaceRecord;
import com.redactado.noray.filesystem.volume.DefaultVolumeManager;
import com.redactado.noray.filesystem.volume.InMemoryVolumeDriver;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class NamespaceJournalTest {

    @TempDir
    Path tmp;

    private ExecutorService executor;
    private DefaultVolumeManager volumeManager;
    private DefaultVirtualFileSystem vfs;
    private Volume volume;
    private Path journalFile;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        volumeManager = new DefaultVolumeManager(new ConcurrentEventBus(executor, (t, c) -> {}));
        volumeManager.registerDriver(new InMemoryVolumeDriver());
        vfs = new DefaultVirtualFileSystem();
        volume = volumeManager.attach(Metadata.builder().put("kind", "memory").build());
        vfs.mount(new Mount("data", VfsPath.parse("/mnt/data"), volume.id(), ""));
        journalFile = tmp.resolve("fs").resolve("namespace.journal");
    }

    @AfterEach
    void tearDown() {
        volumeManager.close();
        executor.shutdownNow();
    }

    private DefaultFileSystemService open() {
        return new DefaultFileSystemService(vfs, volumeManager, new InMemoryObjectStore(), null,
                new FileNamespaceJournal(journalFile, true));
    }

    private static VfsPath p(String s) {
        return VfsPath.parse(s);
    }

    @Test
    void namespaceSurvivesRestart() {
        byte[] content = "persist me".getBytes(StandardCharsets.UTF_8);
        ObjectId id;
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/docs"));
            fs.createFile(p("/mnt/data/docs/a.txt"));
            id = fs.writeFully(p("/mnt/data/docs/a.txt"), content);
            fs.createFile(p("/mnt/data/gone.txt"));
            fs.unlink(p("/mnt/data/gone.txt"));
        }

        try (DefaultFileSystemService fs = open()) {
            assertEquals(NodeType.DIRECTORY, fs.stat(p("/mnt/data/docs")).type());
            assertEquals(NodeType.FILE, fs.stat(p("/mnt/data/docs/a.txt")).type());
            assertArrayEquals(content, fs.readFully(p("/mnt/data/docs/a.txt")));
            assertEquals(id, fs.writeFully(p("/mnt/data/docs/a.txt"), content));

            List<DirectoryEntry> entries = fs.readdir(p("/mnt/data/docs"));
            assertEquals(1, entries.size());
            assertEquals("a.txt", entries.get(0).name());
            assertEquals(FsErrorCode.NOT_FOUND,
                    assertThrows(FsException.class, () -> fs.stat(p("/mnt/data/gone.txt"))).code());
            // the mount root lists only what survived
            assertEquals(List.of("docs"), fs.readdir(p("/mnt/data")).stream().map(DirectoryEntry::name).toList());
        }
    }

    @Test
    void inodeIdsAreNotReusedAfterRestart() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/one"));
        }
        long maxBefore = maxInode();
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/two"));
        }
        List<NamespaceRecord> after = readAll();
        long newId = after.stream()
                .filter(r -> r instanceof NamespaceRecord.Create c && c.path().equals("/mnt/data/two"))
                .mapToLong(r -> ((NamespaceRecord.Create) r).inodeId()).findFirst().orElseThrow();
        assertTrue(newId > maxBefore, "new inode id must be greater than any previous id");
    }

    @Test
    void replayingTwiceIsIdempotentAndAlreadyExistsStillEnforced() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/d"));
        }
        try (DefaultFileSystemService fs = open()) {
            assertEquals(FsErrorCode.ALREADY_EXISTS,
                    assertThrows(FsException.class, () -> fs.mkdir(p("/mnt/data/d"))).code());
        }
    }

    @Test
    void tornTailIsDiscardedAndJournalKeepsWorking() throws IOException {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/keep"));
            fs.mkdir(p("/mnt/data/lost"));
        }
        long full = Files.size(journalFile);
        // simulate a crash in the middle of the last frame
        byte[] bytes = Files.readAllBytes(journalFile);
        Files.write(journalFile, java.util.Arrays.copyOf(bytes, (int) full - 5));

        try (DefaultFileSystemService fs = open()) {
            assertEquals(NodeType.DIRECTORY, fs.stat(p("/mnt/data/keep")).type());
            assertThrows(FsException.class, () -> fs.stat(p("/mnt/data/lost")));
            fs.mkdir(p("/mnt/data/after"));
        }
        try (DefaultFileSystemService fs = open()) {
            assertEquals(NodeType.DIRECTORY, fs.stat(p("/mnt/data/after")).type());
            assertEquals(NodeType.DIRECTORY, fs.stat(p("/mnt/data/keep")).type());
        }
    }

    @Test
    void corruptionInTheMiddleIsReportedNotSilentlyDropped() throws IOException {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/a"));
            fs.mkdir(p("/mnt/data/b"));
            fs.mkdir(p("/mnt/data/c"));
        }
        byte[] bytes = Files.readAllBytes(journalFile);
        bytes[15] ^= 0x7f; // flip bits inside the first frames
        Files.write(journalFile, bytes);

        FsException ex = assertThrows(FsException.class, this::open);
        assertEquals(FsErrorCode.INTERNAL, ex.code());
    }

    @Test
    void rejectsForeignFile() throws IOException {
        Files.createDirectories(journalFile.getParent());
        Files.writeString(journalFile, "this is definitely not a journal");
        assertThrows(FsException.class, this::open);
    }

    @Test
    void compactionKeepsOnlyLiveState() {
        long before;
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/dir"));
            fs.createFile(p("/mnt/data/dir/f"));
            for (int i = 0; i < 200; i++) {
                fs.writeFully(p("/mnt/data/dir/f"), ("v" + i).getBytes(StandardCharsets.UTF_8));
            }
            fs.createFile(p("/mnt/data/tmp"));
            fs.unlink(p("/mnt/data/tmp"));
            before = readAll().size();
            fs.compact();
        }
        long after = readAll().size();
        assertTrue(after < before, "compaction must shrink the journal (" + before + " -> " + after + ")");

        try (DefaultFileSystemService fs = open()) {
            assertArrayEquals("v199".getBytes(StandardCharsets.UTF_8), fs.readFully(p("/mnt/data/dir/f")));
            assertThrows(FsException.class, () -> fs.stat(p("/mnt/data/tmp")));
            fs.mkdir(p("/mnt/data/dir/sub")); // still writable after compaction
        }
    }

    @Test
    void journalFileIsOwnerOnly() throws IOException {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/x"));
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(journalFile);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
    }

    @Test
    void failedJournalWriteDoesNotMutateMemory() {
        NamespaceJournal failing = new NamespaceJournal() {
            boolean armed;
            @Override public List<NamespaceRecord> load() { return List.of(); }
            @Override public void append(NamespaceRecord r) {
                if (armed) throw new FsException(FsErrorCode.INTERNAL, "disk full");
                armed = r instanceof NamespaceRecord.Create c && c.path().equals("/mnt/data/ok") ? true : armed;
            }
            @Override public long recordCount() { return 0; }
            @Override public void rewrite(List<NamespaceRecord> s) { }
            @Override public void close() { }
        };
        try (DefaultFileSystemService fs = new DefaultFileSystemService(vfs, volumeManager,
                new InMemoryObjectStore(), null, failing)) {
            fs.mkdir(p("/mnt/data/ok")); // arms the failure for the next append
            assertThrows(FsException.class, () -> fs.mkdir(p("/mnt/data/ok/child")));
            assertThrows(FsException.class, () -> fs.stat(p("/mnt/data/ok/child")));
            assertTrue(fs.readdir(p("/mnt/data/ok")).isEmpty());
        }
    }

    private List<NamespaceRecord> readAll() {
        FileNamespaceJournal j = new FileNamespaceJournal(journalFile, false);
        try {
            return j.load();
        } finally {
            j.close();
        }
    }

    private long maxInode() {
        return readAll().stream()
                .filter(r -> r instanceof NamespaceRecord.Create)
                .mapToLong(r -> ((NamespaceRecord.Create) r).inodeId()).max().orElse(0);
    }
}
