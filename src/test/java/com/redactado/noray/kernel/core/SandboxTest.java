package com.redactado.noray.kernel.core;

import com.redactado.noray.filesystem.FsModule;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.vfs.VirtualFileSystem;
import com.redactado.noray.kernel.api.*;
import com.redactado.noray.kernel.api.event.ConfigChangedEvent;
import com.redactado.noray.kernel.capability.*;
import com.redactado.noray.net.NetModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SandboxTest {

    @TempDir
    Path tmp;

    /** A module that just keeps the context it was given. */
    static final class Probe implements KernelModule {
        final AtomicReference<KernelContext> ctx = new AtomicReference<>();
        final Set<String> wants;
        Probe(Set<String> wants) { this.wants = wants; }
        @Override public String id() { return "probe"; }
        @Override public Set<String> dependencies() { return Set.of("noray.fs"); }
        @Override public Set<String> requestedCapabilities() { return wants; }
        @Override public void init(KernelContext c) { ctx.set(c); }
        @Override public void shutdown(KernelContext c) { }
    }

    private NorayKernel boot(Probe probe, String grants, boolean sandboxed) {
        Map<String, String> props = new HashMap<>();
        props.put("noray.data.dir", tmp.resolve("data").toString());
        props.put("noray.net.udp.port", "0");
        props.put("noray.net.mesh.psk.insecure", "true");
        props.put("app.visible", "yes");
        props.put("app.hidden", "no");
        props.put("noray.keyring.passphrase", "hunter2");
        if (grants != null) props.put("noray.module.probe.grants", grants);
        NorayKernel k = new NorayKernel(props);
        k.registerModule(new NetModule());
        k.registerModule(new FsModule());
        if (sandboxed) k.registerSandboxedModule(probe); else k.registerModule(probe);
        k.boot();
        return k;
    }

    @Test
    void sandboxedModuleWithoutGrantsCanDoNothing() {
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, null, true)) {
            KernelContext c = p.ctx.get();
            assertNotSame(k.getContext(), c);
            assertThrows(CapabilityDeniedException.class, () -> c.getServiceRegistry().get(FileSystemService.class));
            assertThrows(CapabilityDeniedException.class, () -> c.getServiceRegistry().require(VirtualFileSystem.class));
            assertThrows(CapabilityDeniedException.class, () -> c.getConfiguration().get("app.visible", ""));
            assertThrows(CapabilityDeniedException.class, () -> c.getConfiguration().set("app.visible", "x"));
            assertThrows(CapabilityDeniedException.class, c::getExecutionEngine);
            assertThrows(CapabilityDeniedException.class, c::getActiveModuleNames);
            assertThrows(CapabilityDeniedException.class, c::getActiveModules);
            assertThrows(CapabilityDeniedException.class, () -> c.getEventBus().publish(new ConfigChangedEvent("a", "b", "c")));
            assertTrue(c.getConfiguration().getAllKeys().isEmpty());
            assertFalse(c.getServiceRegistry().contains(FileSystemService.class));
            assertTrue(k.capabilities().denialCount() >= 8);
        }
    }

    @Test
    void trustedModulesKeepTheRealContext() {
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, null, false)) {
            assertSame(k.getContext(), p.ctx.get());
        }
    }

    @Test
    void serviceGrantGivesExactlyThatService() {
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, "service:FileSystemService", true)) {
            KernelContext c = p.ctx.get();
            assertNotNull(c.getServiceRegistry().require(FileSystemService.class));
            assertThrows(CapabilityDeniedException.class, () -> c.getServiceRegistry().get(VirtualFileSystem.class));
        }
    }

    @Test
    void wildcardNeverCoversSensitiveServicesOrSecrets() {
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, "service:*, config.read:*", true)) {
            KernelContext c = p.ctx.get();
            assertNotNull(c.getServiceRegistry().require(FileSystemService.class));
            assertThrows(CapabilityDeniedException.class, () -> c.getServiceRegistry().get(CapabilityRegistry.class));
            assertThrows(CapabilityDeniedException.class, () -> c.getServiceRegistry().get(com.redactado.noray.filesystem.volume.VolumeManager.class));
            assertEquals("yes", c.getConfiguration().get("app.visible", ""));
            assertThrows(CapabilityDeniedException.class, () -> c.getConfiguration().get("noray.keyring.passphrase", ""));
            assertFalse(c.getConfiguration().getAllKeys().contains("noray.keyring.passphrase"));
            assertTrue(c.getConfiguration().getAllKeys().contains("app.hidden"));
        }
    }

    @Test
    void exactGrantForASecretIsHonoured() {
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, "config.read:noray.keyring.passphrase", true)) {
            assertEquals("hunter2", p.ctx.get().getConfiguration().get("noray.keyring.passphrase", ""));
        }
    }

    @Test
    void configPrefixesAndWrites() {
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, "config.read:app.vis*, config.write:app.mine.*", true)) {
            Configuration c = p.ctx.get().getConfiguration();
            assertEquals("yes", c.get("app.visible", ""));
            assertThrows(CapabilityDeniedException.class, () -> c.get("app.hidden", ""));
            c.set("app.mine.x", "1");
            assertThrows(CapabilityDeniedException.class, () -> c.set("app.other", "1"));
            assertThrows(CapabilityDeniedException.class, () -> c.set("noray.fs.blob.max.mib", "1"));
        }
    }

    @Test
    void fsGrantsAreScopedAndWriteImpliesRead() {
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, "fs.write:/mnt/work, fs.read:/mnt/shared", true)) {
            FileSystemService real = k.getContext().getServiceRegistry().require(FileSystemService.class);
            FileSystemService fs = p.ctx.get().getServiceRegistry().require(FileSystemService.class);
            assertNotSame(real, fs);

            // The rest is irrelevant if there is no volume mounted; refusals come first.
            VfsPath work = VfsPath.parse("/mnt/work/a.txt");
            VfsPath shared = VfsPath.parse("/mnt/shared/b.txt");
            VfsPath other = VfsPath.parse("/mnt/other/c.txt");
            VfsPath lookalike = VfsPath.parse("/mnt/workshop/x");

            assertDenied(() -> fs.readFully(other));
            assertDenied(() -> fs.stat(VfsPath.root()));
            assertDenied(() -> fs.readdir(VfsPath.parse("/")));
            assertDenied(() -> fs.writeFully(shared, new byte[1]));   // read-only
            assertDenied(() -> fs.mkdir(VfsPath.parse("/mnt/shared/d")));
            assertDenied(() -> fs.unlink(shared));
            assertDenied(() -> fs.readFully(lookalike));              // /workshop is not /work
            assertDenied(() -> fs.writeFully(other, "x".getBytes(StandardCharsets.UTF_8)));

            // Allowed paths pass through (and fail only for filesystem reasons, never DENIED).
            for (Runnable r : new Runnable[]{
                    () -> fs.readFully(work), () -> fs.stat(shared), () -> fs.readFully(shared),
                    () -> fs.mkdir(VfsPath.parse("/mnt/work/dir"))}) {
                try {
                    r.run();
                } catch (FsException e) {
                    assertNotEquals(FsErrorCode.DENIED, e.code(), e.getMessage());
                }
            }
            assertTrue(k.capabilities().recentDenials(100).stream()
                    .anyMatch(d -> d.moduleId().equals("probe") && d.capability().startsWith("fs.write:/mnt/shared")));
        }
    }

    private static void assertDenied(Runnable r) {
        FsException e = assertThrows(FsException.class, r::run);
        assertEquals(FsErrorCode.DENIED, e.code());
    }

    @Test
    void registeringServicesIsGatedAndCannotReplace() {
        interface Mine { }
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, "service.register:Mine, service.register:FileSystemService", true)) {
            ServiceRegistry r = p.ctx.get().getServiceRegistry();
            Mine m = new Mine() { };
            r.register(Mine.class, m);
            assertSame(m, k.getContext().getServiceRegistry().require(Mine.class));
            // cannot impersonate an existing service even with the name granted
            assertThrows(CapabilityDeniedException.class,
                    () -> r.register(FileSystemService.class, new NoFs()));
            assertThrows(CapabilityDeniedException.class, () -> r.unregister(Mine.class));
        }
    }

    static final class NoFs implements FileSystemService {
        public void mkdir(VfsPath p) { } public void createFile(VfsPath p) { } public void unlink(VfsPath p) { }
        public com.redactado.noray.filesystem.api.DirectoryEntry stat(VfsPath p) { return null; }
        public java.util.List<com.redactado.noray.filesystem.api.DirectoryEntry> readdir(VfsPath p) { return null; }
        public com.redactado.noray.filesystem.store.ObjectId writeFully(VfsPath p, byte[] d) { return null; }
        public byte[] readFully(VfsPath p) { return null; }
    }

    @Test
    void sharedExecutorCannotBeShutDown() {
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, "executor, kernel.modules", true)) {
            ExecutorService ex = p.ctx.get().getExecutionEngine();
            assertThrows(SecurityException.class, ex::shutdown);
            assertThrows(SecurityException.class, ex::shutdownNow);
            assertDoesNotThrow(() -> ex.submit(() -> 1).get());
            assertTrue(p.ctx.get().getActiveModuleNames().contains("noray.fs"));
            assertThrows(CapabilityDeniedException.class, () -> p.ctx.get().getActiveModules());
        }
    }

    @Test
    void eventsAreGatedPerType() {
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, "event.subscribe:ConfigChangedEvent", true)) {
            EventBus bus = p.ctx.get().getEventBus();
            bus.subscribe(ConfigChangedEvent.class, e -> { });
            assertThrows(CapabilityDeniedException.class, () -> bus.publish(new ConfigChangedEvent("a", "b", "c")));
        }
    }

    @Test
    void resourcesAlwaysBelongToTheCaller() throws Exception {
        Probe p = new Probe(Set.of());
        boolean[] closed = {false};
        try (NorayKernel k = boot(p, null, true)) {
            // claims to be the filesystem module; must be filed under "probe" instead
            p.ctx.get().registerResource("noray.fs", () -> closed[0] = true);
        }
        assertTrue(closed[0]);
    }

    @Test
    void invalidGrantsFailTheModuleNotTheKernel() {
        Probe p = new Probe(Set.of());
        try (NorayKernel k = boot(p, "service:Foo, bogus:thing", true)) {
            assertNull(p.ctx.get());
            assertEquals(KernelPhase.RUNNING, k.getContext().getPhase());
        }
    }

    @Test
    void grantParsing() {
        assertTrue(CapabilitySet.parse("fs.write:/a").allows("fs.read", "/a/b"));
        assertFalse(CapabilitySet.parse("fs.read:/a").allows("fs.write", "/a/b"));
        assertFalse(CapabilitySet.parse("fs.read:/a").allows("fs.read", "/ab"));
        assertTrue(CapabilitySet.parse("fs.read:/").allows("fs.read", "/anything"));
        assertThrows(IllegalArgumentException.class, () -> CapabilitySet.parse("fs.read:relative"));
        assertThrows(IllegalArgumentException.class, () -> CapabilitySet.parse("executor:x"));
        assertThrows(IllegalArgumentException.class, () -> CapabilitySet.parse("service"));
        assertTrue(CapabilitySet.parse("").isEmpty());
        assertFalse(CapabilitySet.parse("service:*").allows("service", "Keyring"));
        assertFalse(CapabilitySet.parse("service:*").allows("service", "OnionService"), "anonymity is reviewed by exact grant");
        assertTrue(CapabilitySet.parse("service:OnionService").allows("service", "OnionService"));
        assertTrue(CapabilitySet.parse("service:Keyring").allows("service", "Keyring"));
    }
}
