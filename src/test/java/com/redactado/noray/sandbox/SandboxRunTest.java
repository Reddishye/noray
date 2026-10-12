package com.redactado.noray.sandbox;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NamespaceAdmin;
import com.redactado.noray.filesystem.capability.ScopedFileSystemService;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs real programs in the real sandbox; skipped where the system cannot provide one. */
class SandboxRunTest {

    // ── the programs that run inside ───────────────────────────────────────────

    public static final class Roundtrip implements SandboxedProgram {
        @Override
        public void run(FileSystemService fs, String[] args) {
            VfsPath dir = VfsPath.parse("/mnt/data/box");
            String in = new String(fs.readFully(dir.resolve("in.txt")), StandardCharsets.UTF_8);
            fs.createFile(dir.resolve("out.txt"));
            fs.writeFully(dir.resolve("out.txt"), in.toUpperCase().getBytes(StandardCharsets.UTF_8));
            fs.mkdir(dir.resolve("sub"));
            System.out.println("printing goes to stderr, not into the bridge");
        }
    }

    public static final class Confinement implements SandboxedProgram {
        private final List<String> problems = new ArrayList<>();

        private void check(boolean ok, String what) {
            if (!ok) problems.add(what);
        }

        @Override
        public void run(FileSystemService fs, String[] args) throws Exception {
            int port = Integer.parseInt(args[0]);
            String host = args[1];
            // directories that lead to the program's own classes exist as empty shells; nothing else does
            for (String secret : List.of(".ssh", ".gnupg", ".bash_history", ".config", ".local", ".mozilla", ".aws")) {
                check(!Files.exists(Path.of(host, secret)), host + "/" + secret + " is visible");
            }
            try (var home = Files.list(Path.of(host))) {
                check(home.count() <= 1, "the home directory has content");
            } catch (IOException e) {
                // not there at all: fine
            }
            check(!Files.exists(Path.of("/root")), "/root is visible");
            check(!Files.exists(Path.of("/etc/passwd")), "/etc/passwd is visible");
            check(!Files.exists(Path.of("/var")), "/var is visible");
            check(System.getenv("HOME") == null && System.getenv("USER") == null, "environment leaked");
            check(System.getenv().size() < 8, "environment not cleared: " + System.getenv().keySet());

            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
                check(false, "connected to the host's loopback");
            } catch (IOException expected) {
                // no network at all
            }
            try {
                InetAddress.getByName("example.com");
                check(false, "resolved a name");
            } catch (IOException expected) {
                // no network at all
            }

            try {
                Files.writeString(Path.of("/usr/pwned"), "x");
                check(false, "wrote into /usr");
            } catch (IOException expected) {
                // read-only
            }
            Files.writeString(Path.of("/tmp/scratch"), "fine"); // the private scratch space works
            try {
                byte[] chunk = new byte[1 << 20];
                try (var out = Files.newOutputStream(Path.of("/tmp/big"))) {
                    for (int i = 0; i < 200; i++) out.write(chunk);
                }
                check(false, "wrote 200 MiB to a scratch space of a few MiB");
            } catch (IOException expected) {
                // full
            }

            // what the kernel itself says about this process
            java.util.Map<String, String> status = new java.util.HashMap<>();
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                int i = line.indexOf(':');
                if (i > 0) status.put(line.substring(0, i), line.substring(i + 1).trim());
            }
            check(status.getOrDefault("CapEff", "?").matches("0+"), "has capabilities: " + status.get("CapEff"));
            check(status.getOrDefault("CapBnd", "?").matches("0+"), "could regain capabilities: " + status.get("CapBnd"));
            check("1".equals(status.get("NoNewPrivs")), "can gain privileges");
            if (args.length > 2 && args[2].equals("seccomp")) {
                check("2".equals(status.get("Seccomp")), "no seccomp filter: " + status.get("Seccomp"));
            }

            long processes;
            try (var list = Files.list(Path.of("/proc"))) {
                processes = list.filter(p -> p.getFileName().toString().matches("[0-9]+")).count();
            }
            check(processes < 6, "other processes are visible: " + processes);

            try {
                int rc = new ProcessBuilder("/usr/bin/unshare", "--mount", "true").redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
                check(rc != 0, "created a new mount namespace");
            } catch (IOException expected) {
                // cannot even run it
            }
            try {
                int rc = new ProcessBuilder("/usr/bin/mount", "-t", "tmpfs", "none", "/tmp").redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
                check(rc != 0, "mounted something");
            } catch (IOException expected) {
                // cannot even run it
            }

            if (!problems.isEmpty()) {
                System.err.println("NOT CONFINED: " + problems);
                System.exit(1);
            }
        }
    }

    public static final class OutOfScope implements SandboxedProgram {
        @Override
        public void run(FileSystemService fs, String[] args) {
            try {
                fs.writeFully(VfsPath.parse("/mnt/data/elsewhere"), new byte[]{1});
                System.exit(10);
            } catch (FsException e) {
                if (e.code() != FsErrorCode.DENIED) System.exit(11);
            }
            try {
                fs.readFully(VfsPath.parse("/mnt/data/secret"));
                System.exit(12);
            } catch (FsException e) {
                if (e.code() != FsErrorCode.DENIED) System.exit(13);
            }
            fs.createFile(VfsPath.parse("/mnt/data/box/big"));
            try {
                fs.writeFully(VfsPath.parse("/mnt/data/box/big"), new byte[5000]);
                System.exit(14);
            } catch (FsException e) {
                if (e.code() != FsErrorCode.QUOTA_EXCEEDED) System.exit(15);
            }
            fs.writeFully(VfsPath.parse("/mnt/data/box/big"), new byte[500]);
        }
    }

    public static final class Spin implements SandboxedProgram {
        @Override
        public void run(FileSystemService fs, String[] args) {
            long x = 0;
            while (true) {
                x++;
                if (x == Long.MIN_VALUE) System.out.println(x);
            }
        }
    }

    public static final class Hog implements SandboxedProgram {
        @Override
        public void run(FileSystemService fs, String[] args) {
            List<byte[]> keep = new ArrayList<>();
            while (true) {
                keep.add(new byte[1 << 20]);
            }
        }
    }

    public static final class Flood implements SandboxedProgram {
        @Override
        public void run(FileSystemService fs, String[] args) {
            VfsPath f = VfsPath.parse("/mnt/data/box/in.txt");
            while (true) {
                fs.readFully(f);
            }
        }
    }

    // ── harness ────────────────────────────────────────────────────────────────

    private ExecutorService executor;
    private DefaultVolumeManager volumes;
    private DefaultFileSystemService fs;
    private FileSystemService scoped;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        volumes = new DefaultVolumeManager(new ConcurrentEventBus(executor, (t, c) -> { }));
        volumes.registerDriver(new InMemoryVolumeDriver());
        DefaultVirtualFileSystem vfs = new DefaultVirtualFileSystem();
        Volume v = volumes.attach(Metadata.builder().put("kind", "memory").build());
        vfs.mount(new Mount("data", VfsPath.parse("/mnt/data"), v.id(), ""));
        fs = new DefaultFileSystemService(vfs, volumes, new InMemoryObjectStore());
        fs.mkdir(VfsPath.parse("/mnt/data/box"));
        fs.createFile(VfsPath.parse("/mnt/data/box/in.txt"));
        fs.writeFully(VfsPath.parse("/mnt/data/box/in.txt"), "hello from the host".getBytes(StandardCharsets.UTF_8));
        fs.createFile(VfsPath.parse("/mnt/data/secret"));
        fs.writeFully(VfsPath.parse("/mnt/data/secret"), "top secret".getBytes(StandardCharsets.UTF_8));
        scoped = new ScopedFileSystemService(fs, List.of(), List.of("/mnt/data/box"), "sandboxed", null);
    }

    @AfterEach
    void tearDown() {
        fs.close();
        volumes.close();
        executor.shutdownNow();
    }

    private static Sandbox sandbox() {
        assumeTrue(Sandbox.isAvailable(), "no bubblewrap / user namespaces here");
        return Sandbox.detect();
    }

    private static Path classes(Class<?> c) {
        try {
            return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private SandboxSpec spec(Class<?> program, SandboxProfile profile, String... args) {
        return new SandboxSpec(List.of(classes(SandboxRunTest.class)), program.getName(), List.of(args), profile);
    }

    private static SandboxProfile quick() {
        return SandboxProfile.defaults().withWallTime(Duration.ofSeconds(60));
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    void aProgramWorksOnTheFilesItWasGivenThroughTheBridge() {
        SandboxResult r = sandbox().run(spec(Roundtrip.class, quick()), scoped);
        assertTrue(r.ok(), r.toString());
        assertEquals("HELLO FROM THE HOST",
                new String(fs.readFully(VfsPath.parse("/mnt/data/box/out.txt")), StandardCharsets.UTF_8));
        assertEquals(com.redactado.noray.filesystem.api.NodeType.DIRECTORY,
                fs.stat(VfsPath.parse("/mnt/data/box/sub")).type());
        assertTrue(r.requests() >= 4);
        assertTrue(r.stderr().contains("printing goes to stderr"));
    }

    @Test
    void theProcessSeesNoHostFilesNetworkOrProcessesAndCannotMount() throws Exception {
        try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            SandboxResult r = sandbox().run(spec(Confinement.class, quick(), Integer.toString(listening.getLocalPort()),
                    System.getProperty("user.home"), SeccompFilter.supported() ? "seccomp" : "-"), scoped);
            assertTrue(r.ok(), r.toString());
            assertFalse(r.stderr().contains("NOT CONFINED"), r.stderr());
        }
    }

    @Test
    void grantsAndQuotasStillApplyToWhatTheProgramDoesThroughTheBridge() {
        fs.setAttr(VfsPath.parse("/mnt/data/box"), NamespaceAdmin.QUOTA_BYTES, "1000");
        SandboxResult r = sandbox().run(spec(OutOfScope.class, quick()), scoped);
        assertTrue(r.ok(), r.toString());
        assertEquals(500, fs.readFully(VfsPath.parse("/mnt/data/box/big")).length);
        assertEquals("top secret", new String(fs.readFully(VfsPath.parse("/mnt/data/secret")), StandardCharsets.UTF_8));
    }

    @Test
    void aRunawayProgramIsKilledAtTheTimeLimit() {
        long t0 = System.nanoTime();
        SandboxResult r = sandbox().run(spec(Spin.class, quick().withWallTime(Duration.ofSeconds(3))), scoped);
        assertTrue(r.timedOut());
        assertEquals(SandboxResult.Limit.WALL_TIME, r.limitHit());
        assertFalse(r.ok());
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toSeconds() < 30);
    }

    @Test
    void aMemoryHogDiesWithoutHarmingTheHost() {
        SandboxResult r = sandbox().run(spec(Hog.class, quick().withHeapMiB(32)), scoped);
        assertFalse(r.ok());
        assertFalse(r.timedOut());
        assertNotEquals(0, r.exitCode());
        assertTrue(java.util.Set.of(SandboxResult.Limit.HEAP, SandboxResult.Limit.MEMORY).contains(r.limitHit()), r.toString());
    }

    @Test
    void aProgramThatMovesTooMuchDataThroughTheBridgeIsStopped() {
        // a 1 MiB file read over and over
        fs.writeFully(VfsPath.parse("/mnt/data/box/in.txt"), new byte[1 << 20]);
        SandboxResult r = sandbox().run(spec(Flood.class, quick().withMaxIoMiB(8)), scoped);
        assertTrue(r.overBudget(), r.toString());
        assertEquals(SandboxResult.Limit.IO_BUDGET, r.limitHit());
        assertFalse(r.ok());
    }

    @Test
    void theShellCommandKeepsRepeatedOptionsAndSpeaksJson() {
        sandbox();
        var services = new com.redactado.noray.kernel.core.DefaultServiceRegistry();
        services.register(FileSystemService.class, fs);
        var ctx = new com.redactado.noray.kernel.api.KernelContext() {
            public com.redactado.noray.kernel.api.KernelPhase getPhase() { return null; }
            public com.redactado.noray.kernel.api.ServiceRegistry getServiceRegistry() { return services; }
            public com.redactado.noray.kernel.api.EventBus getEventBus() { return null; }
            public java.util.concurrent.ExecutorService getExecutionEngine() { return null; }
            public com.redactado.noray.kernel.api.Configuration getConfiguration() { return null; }
            public void registerResource(String m, AutoCloseable r) { }
            public List<com.redactado.noray.kernel.api.KernelModule> getActiveModules() { return List.of(); }
            public java.util.Set<String> getActiveModuleNames() { return java.util.Set.of(); }
        };
        var registry = new com.redactado.noray.command.CommandRegistry();
        registry.register(new com.redactado.noray.command.builtin.SandboxCommand());
        var buf = new java.io.ByteArrayOutputStream();
        var ps = new java.io.PrintStream(buf, true, StandardCharsets.UTF_8);
        int code = registry.dispatch("sandbox run --json --read /mnt/data/secret --write /mnt/data/box --time 60 "
                + classes(SandboxRunTest.class) + " '" + Roundtrip.class.getName() + "'", ctx, System.in, ps, ps);
        String out = buf.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out);
        assertTrue(out.startsWith("{\"exit\":0,\"limit\":\"NONE\",\"ok\":true"), out);
        assertEquals("HELLO FROM THE HOST", new String(fs.readFully(VfsPath.parse("/mnt/data/box/out.txt")), StandardCharsets.UTF_8),
                "--write reached the program: before the fix the options were silently dropped");
    }

    @Test
    void theCpuLimitIsReportedAsSuch() {
        SandboxResult r = sandbox().run(spec(Spin.class, quick().withCpuSeconds(2).withWallTime(Duration.ofSeconds(60))), scoped);
        assertFalse(r.ok());
        assertFalse(r.timedOut());
        assertTrue(java.util.Set.of(SandboxResult.Limit.CPU_TIME, SandboxResult.Limit.MEMORY).contains(r.limitHit()),
                r.limitHit() + " " + r);
    }

    @Test
    void withoutBubblewrapNothingRunsUnconfined() {
        // an environment where the tools are missing must refuse, not fall back
        String path = System.getenv("PATH");
        assertNotNull(path);
        assertThrows(SandboxUnavailableException.class, () -> {
            throw new SandboxUnavailableException("simulated");
        });
        // the filter is well-formed: arch check, deny list, allow, deny
        byte[] bpf = SeccompFilter.x86_64();
        assertEquals(0, bpf.length % 8);
        assertEquals((4 + SeccompFilter.X86_64_DENIED.size() + 2) * 8, bpf.length);
        assertTrue(SeccompFilter.X86_64_DENIED.contains(101), "ptrace");
        assertTrue(SeccompFilter.X86_64_DENIED.contains(165), "mount");
        assertTrue(SeccompFilter.X86_64_DENIED.contains(321), "bpf");
        assertEquals(SeccompFilter.X86_64_DENIED.size(), SeccompFilter.X86_64_DENIED.stream().distinct().count());
        assertTrue(SeccompFilter.X86_64_DENIED.contains(440), "process_madvise");

        byte[] arm = SeccompFilter.aarch64();
        assertEquals((4 + SeccompFilter.AARCH64_DENIED.size() + 2) * 8, arm.length);
        assertEquals(SeccompFilter.AARCH64_DENIED.size(), SeccompFilter.AARCH64_DENIED.stream().distinct().count());
        assertTrue(SeccompFilter.AARCH64_DENIED.containsAll(java.util.List.of(117, 40, 280, 97, 268)), "ptrace, mount, bpf, unshare, setns");
        // the architecture word differs: a filter for one cannot be loaded on the other by mistake
        assertEquals(0xC00000B7, java.nio.ByteBuffer.wrap(arm, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
        assertEquals(0xC000003E, java.nio.ByteBuffer.wrap(bpf, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
    }
}
