package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.StorageAdmin;
import com.redactado.noray.filesystem.config.FsConfigService;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.vfs.VirtualFileSystem;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.core.NorayKernel;
import com.redactado.noray.net.NetModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** End to end: encrypted namespace, pool mount over several engine volumes, restart, policies. */
class PoolIntegrationTest {

    @TempDir
    Path tmp;

    private NorayKernel kernel(String passphrase) {
        Map<String, String> props = new HashMap<>();
        props.put("noray.data.dir", tmp.resolve("data").toString());
        props.put("noray.net.udp.port", "0");
        props.put("noray.net.mesh.psk.insecure", "true");
        props.put("noray.keyring.argon2.insecure", "true");
        props.put("noray.keyring.argon2.memory.kib", "64");
        props.put("noray.keyring.argon2.iterations", "1");
        props.put("noray.fs.namespace.sync", "false");
        if (passphrase != null) {
            props.put("noray.keyring.passphrase", passphrase);
        }
        NorayKernel k = new NorayKernel(props);
        k.registerModule(new NetModule());
        k.registerModule(new FsModule());
        return k;
    }

    private void buildPool(KernelContext ctx, int volumes) {
        var registry = ctx.getServiceRegistry();
        FsConfigService cfg = registry.require(FsConfigService.class);
        for (int i = 0; i < volumes; i++) {
            cfg.addVolume(Metadata.builder().put("kind", "local").put("path", tmp.resolve("disk" + i).toString())
                    .put("engine", "true").put("domain", "d" + i).build());
        }
        Volume pool = cfg.addVolume(Metadata.builder().put("kind", "pool").put("policy", "tiered").build());
        registry.require(VirtualFileSystem.class).mount(new Mount("pool", VfsPath.parse("/mnt/pool"), pool.id(), ""));
    }

    private int blobFiles(int volumes) throws IOException {
        int n = 0;
        for (int i = 0; i < volumes; i++) {
            Path dir = tmp.resolve("disk" + i);
            if (Files.exists(dir)) {
                try (Stream<Path> s = Files.walk(dir)) {
                    n += (int) s.filter(Files::isRegularFile).count();
                }
            }
        }
        return n;
    }

    private static byte[] sample(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        for (int i = 0; i < n / 2; i++) b[i] = (byte) ("hello noray ".charAt(i % 12));
        return b;
    }

    @Test
    void poolFilesSurviveRestartAndNothingSensitiveIsOnDiskInTheClear() throws Exception {
        byte[] data = sample(300_000, 1);
        VfsPath file = VfsPath.parse("/mnt/pool/reports/q3-secret-merger.pdf");
        try (NorayKernel k = kernel("correct horse battery staple")) {
            k.boot();
            buildPool(k.getContext(), 10);
            FileSystemService fs = k.getContext().getServiceRegistry().require(FileSystemService.class);
            fs.mkdir(VfsPath.parse("/mnt/pool/reports"));
            fs.createFile(file);
            fs.writeFully(file, data);
            assertArrayEquals(data, fs.readFully(file));
            k.shutdown();
        }

        String journal = new String(Files.readAllBytes(tmp.resolve("data/fs/namespace.journal")), StandardCharsets.ISO_8859_1);
        assertFalse(journal.contains("q3-secret-merger") || journal.contains("noray-ref"));
        assertTrue(Files.exists(tmp.resolve("data/keyring")));

        try (NorayKernel k = kernel("correct horse battery staple")) {
            k.boot();
            FileSystemService fs = k.getContext().getServiceRegistry().require(FileSystemService.class);
            assertArrayEquals(data, fs.readFully(file));
            assertEquals("tiered", k.getContext().getServiceRegistry().require(StorageAdmin.class).storageClassOf(file));
            k.shutdown();
        }
    }

    @Test
    void bootRefusesAWrongOrMissingPassphrase() {
        try (NorayKernel k = kernel("right")) {
            k.boot();
            buildPool(k.getContext(), 2);
            k.shutdown();
        }
        try (NorayKernel k = kernel("wrong")) {
            assertThrows(RuntimeException.class, k::boot);
        }
        try (NorayKernel k = kernel(null)) {
            assertThrows(RuntimeException.class, k::boot);
        }
        try (NorayKernel k = kernel("right")) {
            k.boot();
            k.shutdown();
        }
    }

    @Test
    void poolMountsNeedTheKeyring() {
        try (NorayKernel k = kernel(null)) {
            k.boot();
            var registry = k.getContext().getServiceRegistry();
            FsConfigService cfg = registry.require(FsConfigService.class);
            Volume pool = cfg.addVolume(Metadata.builder().put("kind", "pool").build());
            registry.require(VirtualFileSystem.class).mount(new Mount("pool", VfsPath.parse("/mnt/pool"), pool.id(), ""));
            FileSystemService fs = registry.require(FileSystemService.class);
            fs.createFile(VfsPath.parse("/mnt/pool/a"));
            FsException e = assertThrows(FsException.class, () -> fs.writeFully(VfsPath.parse("/mnt/pool/a"), new byte[]{1}));
            assertEquals(FsErrorCode.VOLUME_UNAVAILABLE, e.code());
            k.shutdown();
        }
    }

    @Test
    void directoryPoliciesAreInheritedPersistedAndReapplied() throws Exception {
        byte[] data = sample(120_000, 2);
        VfsPath docs = VfsPath.parse("/mnt/pool/docs");
        VfsPath cold = VfsPath.parse("/mnt/pool/docs/cold");
        VfsPath f1 = VfsPath.parse("/mnt/pool/docs/a.bin");
        VfsPath f2 = VfsPath.parse("/mnt/pool/docs/cold/b.bin");
        try (NorayKernel k = kernel("pw")) {
            k.boot();
            buildPool(k.getContext(), 10);
            var registry = k.getContext().getServiceRegistry();
            FileSystemService fs = registry.require(FileSystemService.class);
            StorageAdmin admin = registry.require(StorageAdmin.class);
            fs.mkdir(docs);
            fs.mkdir(cold);
            admin.setPolicy(cold, "archive");
            fs.createFile(f1);
            fs.createFile(f2);
            fs.writeFully(f1, data);
            fs.writeFully(f2, data);
            assertEquals("tiered", admin.storageClassOf(f1));
            assertEquals("archive", admin.storageClassOf(f2));
            assertEquals("archive", admin.policyOf(f2));
            assertEquals("tiered", admin.policyOf(f1));
            assertEquals(FsErrorCode.NOT_FOUND,
                    assertThrows(FsException.class, () -> admin.setPolicy(docs, "does-not-exist")).code());
            k.shutdown();
        }
        try (NorayKernel k = kernel("pw")) {
            k.boot();
            var registry = k.getContext().getServiceRegistry();
            FileSystemService fs = registry.require(FileSystemService.class);
            StorageAdmin admin = registry.require(StorageAdmin.class);
            assertEquals("archive", admin.policyOf(f2), "directory policy must survive restart");

            admin.setPolicy(docs, "archive");
            admin.setPolicy(cold, ""); // inherit from docs
            assertEquals(1, admin.applyPolicy(docs), "only f1 changes class");
            assertEquals("archive", admin.storageClassOf(f1));
            assertArrayEquals(data, fs.readFully(f1));
            assertArrayEquals(data, fs.readFully(f2));
            assertEquals(0, admin.applyPolicy(docs), "applying again is a no-op");
            k.shutdown();
        }
    }

    @Test
    void unlinkAndOverwriteReleaseTheirBlobs() throws Exception {
        try (NorayKernel k = kernel("pw")) {
            k.boot();
            buildPool(k.getContext(), 10);
            FileSystemService fs = k.getContext().getServiceRegistry().require(FileSystemService.class);
            VfsPath f = VfsPath.parse("/mnt/pool/x.bin");
            fs.createFile(f);
            assertEquals(0, blobFiles(10));
            fs.writeFully(f, sample(200_000, 3));
            int one = blobFiles(10);
            assertTrue(one > 0);
            fs.writeFully(f, sample(200_000, 4)); // overwrite: old version must be released
            assertEquals(one, blobFiles(10), "overwrite should not leak the previous version");
            assertArrayEquals(sample(200_000, 4), fs.readFully(f));
            fs.unlink(f);
            assertEquals(0, blobFiles(10), "unlink should release everything");
            k.shutdown();
        }
    }
}
