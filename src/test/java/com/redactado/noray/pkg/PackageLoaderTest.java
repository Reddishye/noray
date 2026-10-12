package com.redactado.noray.pkg;

import com.redactado.noray.filesystem.FsModule;
import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.config.FsConfigService;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.vfs.DefaultFileSystemService;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.vfs.VirtualFileSystem;
import com.redactado.noray.filesystem.vfs.journal.FileNamespaceJournal;
import com.redactado.noray.filesystem.volume.DefaultVolumeManager;
import com.redactado.noray.filesystem.volume.InMemoryVolumeDriver;
import com.redactado.noray.identity.SigningKey;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelModule;
import com.redactado.noray.kernel.capability.CapabilityRegistry;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import com.redactado.noray.kernel.core.NorayKernel;
import com.redactado.noray.net.NetModule;
import com.redactado.noray.pkg.PackageManifest.Sandboxing;
import com.redactado.noray.sandbox.Sandbox;
import com.redactado.noray.sandbox.SandboxProfile;
import com.redactado.noray.sandbox.SandboxResult;
import com.redactado.noray.sandbox.SandboxedProgram;
import com.redactado.noray.web.WebApps;
import com.redactado.noray.web.WebModule;
import com.redactado.noray.web.server.AccessTokens;
import com.redactado.noray.web.server.Mounts;
import com.redactado.noray.web.server.WebServer;
import com.redactado.noray.web.ui.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Packages end to end: compiled on the fly, signed, installed, approved, then loaded by a real kernel or sandbox. */
class PackageLoaderTest {

    @TempDir
    Path tmp;

    private static final String MODULE = """
            package com.example.mod;
            public final class Mod implements com.redactado.noray.kernel.api.KernelModule {
                public String id() { return "%s"; }
                public void init(com.redactado.noray.kernel.api.KernelContext c) {
                    System.setProperty("pkgtest.%s", c.getClass().getSimpleName());
                }
                public void shutdown(com.redactado.noray.kernel.api.KernelContext c) { }
            }
            """;

    private PackageManager trustedStore(SigningKey key) {
        PackageManager pm = new PackageManager(tmp.resolve("data/packages"));
        pm.pinPublisher(TestPackages.publisher("example", true, 1, key));
        return pm;
    }

    private NorayKernel kernel(Map<String, String> extra) {
        Map<String, String> props = new HashMap<>();
        props.put("noray.data.dir", tmp.resolve("data").toString());
        props.put("noray.net.udp.port", "0");
        props.put("noray.net.mesh.psk.insecure", "true");
        props.putAll(extra);
        return new NorayKernel(props);
    }

    private void installJvm(PackageManager pm, SigningKey key, String id, String moduleId, String grants) throws Exception {
        byte[] jar = TestPackages.compileJar(tmp.resolve("build-" + id), "com.example.mod.Mod", MODULE.formatted(moduleId, id));
        var d = new PackageManifest.Draft(id, "1.0.0", PackageManifest.NODE_API, Sandboxing.JVM, "com.example.mod.Mod",
                TestPackages.Hex.sha256(jar), jar.length, "example", List.of("config.read:app.*", "executor"), List.of(), "");
        pm.install(TestPackages.archive(d, jar, key), false);
        pm.approve(id, "1.0.0", grants, true);
    }

    @Test
    void anApprovedJvmPackageBootsSandboxedWithExactlyTheApprovedGrants() throws Exception {
        SigningKey key = SigningKey.generate();
        PackageManager pm = trustedStore(key);
        installJvm(pm, key, "com.example.mod", "com.example.mod", "config.read:app.*");
        // a configuration line that tries to widen the grants is ignored
        try (NorayKernel k = kernel(Map.of("noray.module.com.example.mod.grants", "service:*,executor"))) {
            assertEquals(List.of("com.example.mod"), PackageLoader.registerActive(k, pm));
            k.boot();
            assertEquals("RestrictedKernelContext", System.getProperty("pkgtest.com.example.mod"), "ran sandboxed");
            CapabilityRegistry caps = k.capabilities();
            var g = caps.grantsOf("com.example.mod").orElseThrow();
            assertTrue(g.allows("config.read", "app.name"));
            assertFalse(g.allows("executor", ""), "the configuration did not widen the approval");
            assertTrue(pm.audit().tail(5).stream().anyMatch(l -> l.contains("\"event\":\"load\"")));
        }
    }

    @Test
    void aModuleClaimingAnotherIdOrAnUnpinnedPublisherIsNotLoaded() throws Exception {
        SigningKey key = SigningKey.generate();
        PackageManager pm = trustedStore(key);
        installJvm(pm, key, "com.example.liar", "fs", null); // pretends to be the filesystem module
        try (NorayKernel k = kernel(Map.of())) {
            assertTrue(PackageLoader.registerActive(k, pm).isEmpty());
            assertTrue(pm.audit().tail(3).stream().anyMatch(l -> l.contains("load-refused") && l.contains("differs")));
        }
        pm.deactivate("com.example.liar");
        installJvm(pm, key, "com.example.mod", "com.example.mod", null);
        pm.unpinPublisher("example");
        try (NorayKernel k = kernel(Map.of())) {
            assertTrue(PackageLoader.registerActive(k, pm).isEmpty(), "trust is checked at every load");
        }
    }

    @Test
    void aProcessPackageRunsInTheSandboxWithItsApprovedScopeOnly() throws Exception {
        assumeTrue(Sandbox.isAvailable(), "no bubblewrap / user namespaces here");
        String program = """
                package com.example.proc;
                import com.redactado.noray.filesystem.vfs.VfsPath;
                public final class Main implements com.redactado.noray.sandbox.SandboxedProgram {
                    public void run(com.redactado.noray.filesystem.api.FileSystemService fs, String[] args) {
                        fs.createFile(VfsPath.parse("/mnt/data/app/out.txt"));
                        fs.writeFully(VfsPath.parse("/mnt/data/app/out.txt"), ("hello " + args[0]).getBytes());
                        try {
                            fs.readFully(VfsPath.parse("/mnt/data/secret"));
                            throw new IllegalStateException("read outside the approved scope");
                        } catch (com.redactado.noray.filesystem.api.FsException expected) { }
                    }
                }
                """;
        byte[] jar = TestPackages.compileJar(tmp.resolve("build-proc"), "com.example.proc.Main", program);
        SigningKey key = SigningKey.generate();
        PackageManager pm = new PackageManager(tmp.resolve("data/packages"));
        pm.pinPublisher(TestPackages.publisher("example", false, 1, key));
        var d = new PackageManifest.Draft("com.example.proc", "1.0.0", PackageManifest.NODE_API, Sandboxing.PROCESS,
                "com.example.proc.Main", TestPackages.Hex.sha256(jar), jar.length, "example",
                List.of("fs.write:/mnt/data/app"), List.of(), "");
        pm.install(TestPackages.archive(d, jar, key), false);
        pm.approve("com.example.proc", "1.0.0", null, true);

        ExecutorService ex = Executors.newSingleThreadExecutor();
        var volumes = new DefaultVolumeManager(
                new ConcurrentEventBus(ex, (t, c) -> { }));
        volumes.registerDriver(new InMemoryVolumeDriver());
        var vfs = new DefaultVirtualFileSystem();
        var v = volumes.attach(Metadata.builder().put("kind", "memory").build());
        vfs.mount(new Mount("data", VfsPath.parse("/mnt/data"), v.id(), ""));
        try (var fs = new DefaultFileSystemService(vfs, volumes,
                new InMemoryObjectStore(), null,
                new FileNamespaceJournal(tmp.resolve("ns.journal"), true))) {
            fs.mkdir(VfsPath.parse("/mnt/data/app"));
            fs.createFile(VfsPath.parse("/mnt/data/secret"));
            fs.writeFully(VfsPath.parse("/mnt/data/secret"), "top secret".getBytes(StandardCharsets.UTF_8));
            SandboxResult r = pm.run("com.example.proc", List.of("world"), fs, new CapabilityRegistry(),
                    SandboxProfile.defaults().withWallTime(Duration.ofSeconds(60)), Sandbox.detect());
            assertTrue(r.ok(), r.toString());
            assertEquals("hello world", new String(fs.readFully(VfsPath.parse("/mnt/data/app/out.txt")), StandardCharsets.UTF_8));
        } finally {
            volumes.close();
            ex.shutdownNow();
        }
    }

    @Test
    void theStaticHostingExampleRunsAsASignedSandboxedPackage() throws Exception {
        String source = Files.readString(Path.of(
                "examples/static-hosting/src/main/java/org/noray/examples/hosting/StaticHostingModule.java"));
        byte[] jar = TestPackages.compileJar(tmp.resolve("build-hosting"), "org.noray.examples.hosting.StaticHostingModule", source);
        SigningKey key = SigningKey.generate();
        PackageManager pm = trustedStore(key);
        String id = "org.noray.examples.hosting";
        var d = new PackageManifest.Draft(id, "1.0.0", PackageManifest.NODE_API, Sandboxing.JVM,
                "org.noray.examples.hosting.StaticHostingModule", TestPackages.Hex.sha256(jar), jar.length, "example",
                List.of("fs.read:/mnt/sites", "web.site:/site", "web.app", "config.read:hosting.*"), List.of(), "static hosting");
        pm.install(TestPackages.archive(d, jar, key), false);
        pm.approve(id, "1.0.0", null, true);

        try (NorayKernel k = kernel(Map.of("noray.keyring.passphrase", "hunter2"))) {
            k.registerModule(new NetModule());
            k.registerModule(new FsModule());
            k.registerModule(new WebModule());
            assertEquals(List.of(id), PackageLoader.registerActive(k, pm));
            k.boot();
            assertTrue(k.getContext().getActiveModuleNames().contains(id), "the module booted sandboxed");

            // content written by the operator (the module itself can only read it)
            var reg = k.getContext().getServiceRegistry();
            var volume = reg.require(FsConfigService.class)
                    .addVolume(Metadata.builder().put("kind", "memory").build());
            reg.require(VirtualFileSystem.class).mount(new Mount(
                    "sites", VfsPath.parse("/mnt/sites"), volume.id(), ""));
            var fs = reg.require(FileSystemService.class);
            var index = VfsPath.parse("/mnt/sites/index.html");
            fs.createFile(index);
            fs.writeFully(index, "<h1>hello from the pool</h1>".getBytes(StandardCharsets.UTF_8));

            var mounts = (Mounts) reg.require(WebApps.class);
            assertTrue(mounts.apps().stream().anyMatch(a -> a.slug().equals(id) && a.owner().equals(id)),
                    "the admin app is mounted under the module's own id");
            var tokens = new AccessTokens(tmp.resolve("tokens"));
            String token = tokens.issue("t");
            var cfg = new WebServer.Config("127.0.0.1", 0, null, new char[0], false,
                    Set.of("127.0.0.1"), Duration.ofMinutes(5), Duration.ofHours(1), "Noray");
            try (var server = new WebServer(cfg, mounts, tokens, Theme.noir())) {
                var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
                String base = "http://127.0.0.1:" + server.port();
                var site = http.send(HttpRequest.newBuilder(URI.create(base + "/site/")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, site.statusCode());
                assertEquals("<h1>hello from the pool</h1>", site.body());
                assertTrue(site.headers().firstValue("Content-Security-Policy").orElseThrow().startsWith("sandbox"));

                var login = http.send(HttpRequest.newBuilder(URI.create(base + "/_nr/login?next=/apps/" + id))
                        .header("Origin", base).header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("token=" + token)).build(),
                        HttpResponse.BodyHandlers.ofString());
                String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
                var admin = http.send(HttpRequest.newBuilder(URI.create(base + "/apps/" + id))
                        .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, admin.statusCode());
                assertTrue(admin.body().contains("index.html"), "the admin page lists the site content");
            }
            // the module could not write its content even if it tried: it only holds fs.read
            assertTrue(k.capabilities().grantsOf(id).orElseThrow().allows("fs.read", "/mnt/sites/x"));
            assertFalse(k.capabilities().grantsOf(id).orElseThrow().allows("fs.write", "/mnt/sites/x"));
        }
    }

    @Test
    void packagesLoadReloadAndUnloadOnARunningKernel() throws Exception {
        SigningKey key = SigningKey.generate();
        PackageManager pm = trustedStore(key);
        String id = "com.example.live";
        String src = """
                package com.example.live;
                public final class Mod implements com.redactado.noray.kernel.api.KernelModule {
                    public String id() { return "com.example.live"; }
                    public void init(com.redactado.noray.kernel.api.KernelContext c) { System.setProperty("pkgtest.live", "%s"); }
                    public void shutdown(com.redactado.noray.kernel.api.KernelContext c) { System.setProperty("pkgtest.live", "stopped"); }
                }
                """;
        for (String v : List.of("1.0.0", "1.1.0")) {
            byte[] jar = TestPackages.compileJar(tmp.resolve("build-live-" + v), "com.example.live.Mod", src.formatted(v));
            var d = new PackageManifest.Draft(id, v, PackageManifest.NODE_API, Sandboxing.JVM, "com.example.live.Mod",
                    TestPackages.Hex.sha256(jar), jar.length, "example", List.of("executor"), List.of(), "");
            pm.install(TestPackages.archive(d, jar, key), false);
        }
        try (NorayKernel k = kernel(Map.of())) {
            k.registerModule(new KernelModule() {
                public String id() { return "core.thing"; }
                public void init(KernelContext c) { }
                public void shutdown(KernelContext c) { }
            });
            k.boot();
            assertThrows(IllegalStateException.class, () -> k.unloadModule("core.thing"), "core modules stay");
            assertEquals("nothing to load", PackageLoader.apply(k, pm, id));
            pm.approve(id, "1.0.0", null, true);
            assertEquals("loaded", PackageLoader.apply(k, pm, id));
            assertEquals("1.0.0", System.getProperty("pkgtest.live"));
            assertTrue(k.getContext().getActiveModuleNames().contains(id));
            assertTrue(k.capabilities().grantsOf(id).orElseThrow().allows("executor", ""));

            pm.approve(id, "1.1.0", "", true); // approved with nothing this time
            assertEquals("reloaded", PackageLoader.apply(k, pm, id));
            assertEquals("1.1.0", System.getProperty("pkgtest.live"));
            assertFalse(k.capabilities().grantsOf(id).orElseThrow().allows("executor", ""), "new grants on reload");

            pm.deactivate(id);
            assertEquals("unloaded", PackageLoader.apply(k, pm, id));
            assertEquals("stopped", System.getProperty("pkgtest.live"));
            assertFalse(k.getContext().getActiveModuleNames().contains(id));
            assertFalse(k.unloadModule("not.loaded"));
        }
    }
}
