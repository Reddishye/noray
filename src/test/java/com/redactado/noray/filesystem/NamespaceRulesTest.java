package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NamespaceAdmin;
import com.redactado.noray.filesystem.capability.ScopedFileSystemService;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.vfs.DefaultFileSystemService;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.vfs.journal.FileNamespaceJournal;
import com.redactado.noray.filesystem.vfs.journal.NamespaceRecord;
import com.redactado.noray.filesystem.volume.DefaultVolumeManager;
import com.redactado.noray.filesystem.volume.InMemoryVolumeDriver;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Per-directory quotas and rules: enforcement, inheritance, persistence, and the finer module grants. */
class NamespaceRulesTest {

    @TempDir
    Path tmp;

    private ExecutorService executor;
    private DefaultVolumeManager volumes;
    private DefaultVirtualFileSystem vfs;
    private Path journalFile;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        volumes = new DefaultVolumeManager(new ConcurrentEventBus(executor, (t, c) -> { }));
        volumes.registerDriver(new InMemoryVolumeDriver());
        vfs = new DefaultVirtualFileSystem();
        Volume v = volumes.attach(Metadata.builder().put("kind", "memory").build());
        vfs.mount(new Mount("data", VfsPath.parse("/mnt/data"), v.id(), ""));
        journalFile = tmp.resolve("fs").resolve("namespace.journal");
    }

    @AfterEach
    void tearDown() {
        volumes.close();
        executor.shutdownNow();
    }

    private DefaultFileSystemService open() {
        return new DefaultFileSystemService(vfs, volumes, new InMemoryObjectStore(), null,
                new FileNamespaceJournal(journalFile, true));
    }

    private static VfsPath p(String s) {
        return VfsPath.parse(s);
    }

    private static FsErrorCode code(Runnable r) {
        return assertThrows(FsException.class, r::run).code();
    }

    @Test
    void byteQuotaCoversTheWholeSubtreeAndFreesUpOnDelete() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/team"));
            fs.mkdir(p("/mnt/data/team/sub"));
            fs.setAttr(p("/mnt/data/team"), NamespaceAdmin.QUOTA_BYTES, "100");
            fs.createFile(p("/mnt/data/team/a"));
            fs.createFile(p("/mnt/data/team/sub/b"));
            fs.writeFully(p("/mnt/data/team/a"), new byte[60]);
            assertEquals(FsErrorCode.QUOTA_EXCEEDED, code(() -> fs.writeFully(p("/mnt/data/team/sub/b"), new byte[50])));
            fs.writeFully(p("/mnt/data/team/sub/b"), new byte[40]);
            assertEquals(new NamespaceAdmin.Usage(100, 2), fs.usageOf(p("/mnt/data/team")));

            // replacing a file only counts the difference
            fs.writeFully(p("/mnt/data/team/a"), new byte[60]);
            assertEquals(FsErrorCode.QUOTA_EXCEEDED, code(() -> fs.writeFully(p("/mnt/data/team/a"), new byte[61])));

            fs.unlink(p("/mnt/data/team/a"));
            fs.createFile(p("/mnt/data/team/c"));
            fs.writeFully(p("/mnt/data/team/c"), new byte[60]);
            // a file outside the subtree is unaffected
            fs.createFile(p("/mnt/data/other"));
            fs.writeFully(p("/mnt/data/other"), new byte[10_000]);
        }
    }

    @Test
    void fileCountQuotaAndPerFileLimit() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/q"));
            fs.setAttr(p("/mnt/data/q"), NamespaceAdmin.QUOTA_FILES, "2");
            fs.setAttr(p("/mnt/data/q"), NamespaceAdmin.MAX_FILE_BYTES, "10");
            fs.createFile(p("/mnt/data/q/1"));
            fs.createFile(p("/mnt/data/q/2"));
            assertEquals(FsErrorCode.QUOTA_EXCEEDED, code(() -> fs.createFile(p("/mnt/data/q/3"))));
            assertEquals(FsErrorCode.QUOTA_EXCEEDED, code(() -> fs.writeFully(p("/mnt/data/q/1"), new byte[11])));
            fs.writeFully(p("/mnt/data/q/1"), new byte[10]);
            fs.unlink(p("/mnt/data/q/2"));
            fs.createFile(p("/mnt/data/q/3"));
        }
    }

    @Test
    void nestedQuotasAllHaveToHaveRoom() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/a"));
            fs.mkdir(p("/mnt/data/a/b"));
            fs.setAttr(p("/mnt/data/a"), NamespaceAdmin.QUOTA_BYTES, "100");
            fs.setAttr(p("/mnt/data/a/b"), NamespaceAdmin.QUOTA_BYTES, "30");
            fs.createFile(p("/mnt/data/a/b/f"));
            fs.createFile(p("/mnt/data/a/g"));
            assertEquals(FsErrorCode.QUOTA_EXCEEDED, code(() -> fs.writeFully(p("/mnt/data/a/b/f"), new byte[31])));
            fs.writeFully(p("/mnt/data/a/b/f"), new byte[30]);
            fs.writeFully(p("/mnt/data/a/g"), new byte[70]);
            assertEquals(FsErrorCode.QUOTA_EXCEEDED, code(() -> fs.writeFully(p("/mnt/data/a/g"), new byte[71])));
        }
    }

    @Test
    void readonlyDeniesEverythingAndASubdirectoryCanRelaxIt() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/vault"));
            fs.mkdir(p("/mnt/data/vault/scratch"));
            fs.createFile(p("/mnt/data/vault/doc"));
            fs.writeFully(p("/mnt/data/vault/doc"), new byte[]{1});
            fs.setAttr(p("/mnt/data/vault"), NamespaceAdmin.READONLY, "true");

            assertEquals(FsErrorCode.READ_ONLY, code(() -> fs.writeFully(p("/mnt/data/vault/doc"), new byte[]{2})));
            assertEquals(FsErrorCode.READ_ONLY, code(() -> fs.createFile(p("/mnt/data/vault/new"))));
            assertEquals(FsErrorCode.READ_ONLY, code(() -> fs.mkdir(p("/mnt/data/vault/d"))));
            assertEquals(FsErrorCode.READ_ONLY, code(() -> fs.unlink(p("/mnt/data/vault/doc"))));
            assertArrayEquals(new byte[]{1}, fs.readFully(p("/mnt/data/vault/doc")));

            fs.setAttr(p("/mnt/data/vault/scratch"), NamespaceAdmin.READONLY, "false");
            fs.createFile(p("/mnt/data/vault/scratch/tmp"));
            fs.writeFully(p("/mnt/data/vault/scratch/tmp"), new byte[]{3});

            fs.setAttr(p("/mnt/data/vault"), NamespaceAdmin.READONLY, "");
            fs.unlink(p("/mnt/data/vault/doc"));
        }
    }

    @Test
    void evidenceDirectoryAcceptsNewFilesOnceAndNeverLosesThem() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/evidence"));
            fs.setAttr(p("/mnt/data/evidence"), NamespaceAdmin.DENY_DELETE, "true");
            fs.setAttr(p("/mnt/data/evidence"), NamespaceAdmin.DENY_OVERWRITE, "true");
            fs.createFile(p("/mnt/data/evidence/log1"));
            fs.writeFully(p("/mnt/data/evidence/log1"), new byte[]{1});
            assertEquals(FsErrorCode.READ_ONLY, code(() -> fs.writeFully(p("/mnt/data/evidence/log1"), new byte[]{2})));
            assertEquals(FsErrorCode.READ_ONLY, code(() -> fs.unlink(p("/mnt/data/evidence/log1"))));
            fs.createFile(p("/mnt/data/evidence/log2"));
            fs.writeFully(p("/mnt/data/evidence/log2"), new byte[]{9});
            assertArrayEquals(new byte[]{1}, fs.readFully(p("/mnt/data/evidence/log1")));
        }
    }

    @Test
    void rulesAndUsageSurviveRestartAndCompaction() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/t"));
            fs.setAttr(p("/mnt/data/t"), NamespaceAdmin.QUOTA_BYTES, "100");
            fs.setAttr(p("/mnt/data/t"), NamespaceAdmin.READONLY, "false");
            fs.createFile(p("/mnt/data/t/f"));
            fs.writeFully(p("/mnt/data/t/f"), new byte[70]);
        }
        for (int round = 0; round < 2; round++) {
            try (DefaultFileSystemService fs = open()) {
                assertEquals("100", fs.attrsOf(p("/mnt/data/t")).get(NamespaceAdmin.QUOTA_BYTES));
                assertEquals(new NamespaceAdmin.Usage(70, 1 + round), fs.usageOf(p("/mnt/data/t")));
                fs.createFile(p("/mnt/data/t/g" + round));
                assertEquals(FsErrorCode.QUOTA_EXCEEDED,
                        code(() -> fs.writeFully(p("/mnt/data/t/g0"), new byte[31])));
                fs.compact();
            }
        }
    }

    @Test
    void unlinkingADirectoryForgetsItsRules() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/t"));
            fs.setAttr(p("/mnt/data/t"), NamespaceAdmin.READONLY, "true");
            fs.setAttr(p("/mnt/data/t"), NamespaceAdmin.READONLY, "");
            fs.unlink(p("/mnt/data/t"));
            fs.mkdir(p("/mnt/data/t"));
            assertTrue(fs.attrsOf(p("/mnt/data/t")).isEmpty());
        }
        try (DefaultFileSystemService fs = open()) {
            assertTrue(fs.attrsOf(p("/mnt/data/t")).isEmpty());
        }
    }

    @Test
    void invalidRulesAreRefused() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/t"));
            fs.createFile(p("/mnt/data/f"));
            assertEquals(FsErrorCode.NOT_FOUND, code(() -> fs.setAttr(p("/mnt/data/t"), "colour", "red")));
            assertEquals(FsErrorCode.INVALID_PATH, code(() -> fs.setAttr(p("/mnt/data/t"), "quota.bytes", "-1")));
            assertEquals(FsErrorCode.INVALID_PATH, code(() -> fs.setAttr(p("/mnt/data/t"), "quota.bytes", "lots")));
            assertEquals(FsErrorCode.INVALID_PATH, code(() -> fs.setAttr(p("/mnt/data/t"), "readonly", "maybe")));
            assertEquals(FsErrorCode.NOT_A_DIRECTORY, code(() -> fs.setAttr(p("/mnt/data/f"), "readonly", "true")));
            assertEquals(FsErrorCode.NOT_FOUND, code(() -> fs.setAttr(p("/mnt/data/missing"), "readonly", "true")));
        }
    }

    @Test
    void lowerQuotaDoesNotTouchExistingDataButBlocksGrowth() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/t"));
            fs.createFile(p("/mnt/data/t/f"));
            fs.writeFully(p("/mnt/data/t/f"), new byte[500]);
            fs.setAttr(p("/mnt/data/t"), NamespaceAdmin.QUOTA_BYTES, "100");
            assertEquals(FsErrorCode.QUOTA_EXCEEDED, code(() -> fs.writeFully(p("/mnt/data/t/f"), new byte[501])));
            fs.writeFully(p("/mnt/data/t/f"), new byte[10]); // shrinking is always allowed
            assertEquals(10, fs.readFully(p("/mnt/data/t/f")).length);
        }
    }

    @Test
    void createOnlyGrantFillsFilesOnceAndDeleteGrantOnlyDeletes() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/inbox"));
            var inbox = new ScopedFileSystemService(fs, List.of(), List.of(), List.of("/mnt/data/inbox"), List.of(),
                    "dropbox", null);
            inbox.createFile(p("/mnt/data/inbox/m1"));
            inbox.writeFully(p("/mnt/data/inbox/m1"), new byte[]{1});
            assertEquals(FsErrorCode.DENIED, code(() -> inbox.writeFully(p("/mnt/data/inbox/m1"), new byte[]{2})));
            assertEquals(FsErrorCode.DENIED, code(() -> inbox.unlink(p("/mnt/data/inbox/m1"))));
            assertEquals(FsErrorCode.DENIED, code(() -> inbox.readFully(p("/mnt/data/inbox/m1"))));
            assertEquals(FsErrorCode.DENIED, code(() -> inbox.createFile(p("/mnt/data/elsewhere"))));

            var janitor = new ScopedFileSystemService(fs, List.of(), List.of(), List.of(), List.of("/mnt/data/inbox"),
                    "janitor", null);
            assertEquals(FsErrorCode.DENIED, code(() -> janitor.createFile(p("/mnt/data/inbox/m2"))));
            janitor.unlink(p("/mnt/data/inbox/m1"));
        }
    }

    @Test
    void writeGrantStillMeansEverythingOnTheSubtree() {
        var cap = com.redactado.noray.kernel.capability.Capability.of("fs.write", "/mnt/data");
        assertTrue(cap.covers(com.redactado.noray.kernel.capability.Capability.of("fs.create", "/mnt/data/x")));
        assertTrue(cap.covers(com.redactado.noray.kernel.capability.Capability.of("fs.delete", "/mnt/data/x")));
        assertTrue(cap.covers(com.redactado.noray.kernel.capability.Capability.of("fs.read", "/mnt/data/x")));
        var create = com.redactado.noray.kernel.capability.Capability.of("fs.create", "/mnt/data");
        assertFalse(create.covers(com.redactado.noray.kernel.capability.Capability.of("fs.read", "/mnt/data/x")));
        assertFalse(create.covers(com.redactado.noray.kernel.capability.Capability.of("fs.delete", "/mnt/data/x")));
    }

    @Test
    void streamingReplayYieldsTheSameRecordsAsLoad() throws Exception {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/d"));
            for (int i = 0; i < 300; i++) {
                fs.createFile(p("/mnt/data/d/f" + i));
                fs.writeFully(p("/mnt/data/d/f" + i), new byte[i]);
            }
            fs.setAttr(p("/mnt/data/d"), NamespaceAdmin.QUOTA_FILES, "1000");
        }
        List<NamespaceRecord> streamed = new ArrayList<>();
        try (var j = new FileNamespaceJournal(journalFile, false)) {
            j.replay(streamed::add);
        }
        List<NamespaceRecord> loaded;
        try (var j = new FileNamespaceJournal(journalFile, false)) {
            loaded = j.load();
        }
        assertEquals(loaded, streamed);
        assertTrue(streamed.size() > 600);
        assertTrue(streamed.stream().anyMatch(r -> r instanceof NamespaceRecord.SetData d && d.size() == 299));
        assertTrue(streamed.stream().anyMatch(r -> r instanceof NamespaceRecord.SetAttr));
    }

    @Test
    void reconcileFindsNothingToCorrectAfterOrdinaryOperations() {
        try (DefaultFileSystemService fs = open()) {
            fs.mkdir(p("/mnt/data/q"));
            fs.mkdir(p("/mnt/data/q/deep"));
            fs.setAttr(p("/mnt/data/q"), NamespaceAdmin.QUOTA_BYTES, "1000");
            fs.setAttr(p("/mnt/data/q/deep"), NamespaceAdmin.QUOTA_FILES, "10");
            fs.createFile(p("/mnt/data/q/a"));
            fs.writeFully(p("/mnt/data/q/a"), new byte[100]);
            fs.createFile(p("/mnt/data/q/deep/b"));
            fs.writeFully(p("/mnt/data/q/deep/b"), new byte[50]);
            fs.writeFully(p("/mnt/data/q/a"), new byte[10]);   // shrink
            fs.unlink(p("/mnt/data/q/deep/b"));
            assertEquals(0, fs.reconcileUsage(), "running counters agree with the namespace");
            assertEquals(new NamespaceAdmin.Usage(10, 1), fs.usageOf(p("/mnt/data/q")));
            // the quota still counts what it should after a reconcile
            fs.createFile(p("/mnt/data/q/c"));
            assertEquals(FsErrorCode.QUOTA_EXCEEDED, code(() -> fs.writeFully(p("/mnt/data/q/c"), new byte[991])));
            fs.writeFully(p("/mnt/data/q/c"), new byte[990]);
        }
    }

    @Test
    void writeIfVersionRefusesAStaleVersionAtomically() {
        try (DefaultFileSystemService fs = open()) {
            VfsPath f = p("/mnt/data/doc.txt");
            fs.createFile(f);
            assertEquals("", fs.version(f), "never written");
            fs.writeIfVersion(f, "", "one".getBytes());
            String v1 = fs.version(f);
            assertNotEquals("", v1);
            fs.writeFully(f, "two (someone else)".getBytes());
            assertEquals(FsErrorCode.CONFLICT, code(() -> fs.writeIfVersion(f, v1, "three".getBytes())));
            assertEquals("two (someone else)", new String(fs.readFully(f)), "the losing write changed nothing");
            fs.writeIfVersion(f, fs.version(f), "three".getBytes());
            assertEquals("three", new String(fs.readFully(f)));
            assertEquals(FsErrorCode.NOT_FOUND, code(() -> fs.version(p("/mnt/data/missing"))));

            // a create-only grant (drop box) cannot overwrite, conditionally or not
            ScopedFileSystemService dropBox = new ScopedFileSystemService(fs, List.of(), List.of(),
                    List.of("/mnt/data"), List.of(), "box", null);
            String v = fs.version(f);
            assertEquals(FsErrorCode.DENIED, code(() -> dropBox.writeIfVersion(f, v, "x".getBytes())));
        }
    }
}
