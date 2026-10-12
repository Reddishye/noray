package com.redactado.noray.sandbox;

import com.redactado.noray.filesystem.api.DirectoryEntry;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.vfs.VfsPath;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs untrusted code in a separate process that can do almost nothing.
 *
 * <p>The process is a child JVM started through <a href="https://github.com/containers/bubblewrap">bubblewrap</a>:
 * <ul>
 *   <li>new user, pid, network, ipc, uts and cgroup namespaces: it has no network (not even loopback), sees no
 *       other process, and has no capabilities; user namespaces cannot be created inside it;</li>
 *   <li>an empty root: only the system libraries, the JDK and the classes of the program are visible, read-only; the
 *       node's data directory, keys, home and environment do not exist for it; its only writable place is a
 *       private in-memory {@code /tmp} of limited size;</li>
 *   <li>a seccomp filter (x86-64, aarch64) that refuses mount, ptrace, bpf, io_uring, keyrings, namespaces and the like;</li>
 *   <li>resource limits: Java heap, address space, CPU time, file size, descriptors, wall time, and (when systemd
 *       can provide it) a cgroup scope with a hard memory and task cap;</li>
 *   <li>its only door is a narrow request/response bridge to a {@link FileSystemService} chosen by the caller,
 *       normally a {@link com.redactado.noray.filesystem.capability.ScopedFileSystemService scoped} one, so the
 *       operator's grants and the namespace quotas apply to it.</li>
 * </ul>
 *
 * <p><b>It fails closed.</b> If the system cannot provide this isolation (no bubblewrap, user namespaces disabled,
 * not Linux), {@link #detect()} throws {@link SandboxUnavailableException} and nothing runs unconfined.
 *
 * <p>What it is not: it does not defend against kernel vulnerabilities reachable through the allowed system calls,
 * it is Linux-only, and a cgroup memory cap is only applied when {@code systemd-run --user} works.
 */
public final class Sandbox {

    private static final System.Logger LOG = System.getLogger(Sandbox.class.getName());

    private final Path bwrap;
    private final Path prlimit;
    private final Path systemdRun; // null when no usable user scope
    private final boolean userNsLock;

    private Sandbox(Path bwrap, Path prlimit, Path systemdRun, boolean userNsLock) {
        this.bwrap = bwrap;
        this.prlimit = prlimit;
        this.systemdRun = systemdRun;
        this.userNsLock = userNsLock;
    }

    /** True when {@link #detect()} would succeed. */
    public static boolean isAvailable() {
        try {
            detect();
            return true;
        } catch (SandboxUnavailableException e) {
            return false;
        }
    }

    /** Checks the system and returns a sandbox, or says exactly why it cannot provide one. */
    public static Sandbox detect() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new SandboxUnavailableException("sandboxing needs Linux (user namespaces + seccomp); on "
                    + System.getProperty("os.name") + " untrusted code is refused. Run the node in a Linux VM to use process packages");
        }
        Path bwrap = which("bwrap");
        if (bwrap == null) {
            throw new SandboxUnavailableException("bubblewrap (bwrap) is not installed (Debian/Ubuntu: apt install bubblewrap; "
                    + "Fedora: dnf install bubblewrap; Arch: pacman -S bubblewrap)");
        }
        Path prlimit = which("prlimit");
        if (prlimit == null) {
            throw new SandboxUnavailableException("prlimit (util-linux) is not installed");
        }
        boolean lock = probe(List.of(bwrap.toString(), "--unshare-all", "--disable-userns", "--die-with-parent",
                "--ro-bind", "/usr", "/usr", "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib", "/lib64",
                "/usr/bin/true"));
        boolean plain = lock || probe(List.of(bwrap.toString(), "--unshare-all", "--die-with-parent",
                "--ro-bind", "/usr", "/usr", "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib", "/lib64",
                "/usr/bin/true"));
        if (!plain) {
            throw new SandboxUnavailableException("bubblewrap cannot create the namespaces here: unprivileged user namespaces "
                    + "are disabled (check kernel.unprivileged_userns_clone=1, and on Ubuntu 24.04+ "
                    + "kernel.apparmor_restrict_unprivileged_userns=0 or an AppArmor profile for bwrap), or the node runs in a "
                    + "container that forbids them (Docker: --security-opt seccomp=unconfined --security-opt apparmor=unconfined, "
                    + "or run the node on the host). Nothing runs unconfined meanwhile");
        }
        Path systemd = which("systemd-run");
        if (systemd != null && !probe(List.of(systemd.toString(), "--user", "--scope", "--quiet",
                "-p", "MemoryMax=64M", "/usr/bin/true"))) {
            systemd = null;
        }
        return new Sandbox(bwrap, prlimit, systemd, lock);
    }

    /** Whether runs are also confined by a cgroup memory/task cap on this system. */
    public boolean hasCgroupLimits() {
        return systemdRun != null;
    }

    private static Path which(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(":")) {
            Path p = Path.of(dir, name);
            if (Files.isExecutable(p)) {
                return p;
            }
        }
        return null;
    }

    private static boolean probe(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── running ────────────────────────────────────────────────────────────────

    /**
     * Runs the program and blocks until it ends or is killed.
     *
     * @param fs what the program may touch through the bridge; give it a scoped service
     */
    public SandboxResult run(SandboxSpec spec, FileSystemService fs) {
        Path filter = null;
        try {
            byte[] bpf = SeccompFilter.forCurrentArch();
            if (bpf != null) {
                filter = Files.createTempFile("noray-seccomp", ".bpf",
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
                Files.write(filter, bpf);
            }
            List<String> cmd = command(spec, filter);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().put("PATH", "/usr/bin");
            if (spec.profile().cgroup() && systemdRun != null) {
                // the launcher talks to the user's systemd; bubblewrap then clears the environment for the program
                for (String name : List.of("XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS")) {
                    String v = System.getenv(name);
                    if (v != null) {
                        pb.environment().put(name, v);
                    }
                }
            }
            Process process = pb.start();
            return supervise(process, spec.profile(), fs);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot start the sandbox: " + e.getMessage(), e);
        } finally {
            if (filter != null) {
                try {
                    Files.deleteIfExists(filter);
                } catch (IOException ignored) {
                    // temp file in the temp dir; owner-only and harmless
                }
            }
        }
    }

    List<String> command(SandboxSpec spec, Path seccomp) {
        SandboxProfile prof = spec.profile();
        List<String> cmd = new ArrayList<>();
        if (seccomp != null) {
            cmd.addAll(List.of("/bin/sh", "-c", "exec 3<\"$1\"; shift; exec \"$@\"", "sh", seccomp.toString()));
        }
        if (prof.cgroup() && systemdRun != null) {
            cmd.addAll(List.of(systemdRun.toString(), "--user", "--scope", "--quiet",
                    "-p", "MemoryMax=" + (prof.heapMiB() + 512) + "M", "-p", "MemorySwapMax=0", "-p", "TasksMax=256"));
        }
        long asBytes = (prof.heapMiB() + 2048L) * 1024 * 1024;
        cmd.addAll(List.of(prlimit.toString(), "--cpu=" + prof.cpuSeconds(), "--nofile=" + prof.maxOpenFiles(),
                "--fsize=" + prof.maxFileMiB() * 1024L * 1024, "--core=0", "--as=" + asBytes, "--"));

        cmd.add(bwrap.toString());
        cmd.addAll(List.of("--unshare-all", "--die-with-parent", "--new-session", "--clearenv"));
        if (userNsLock) {
            cmd.add("--disable-userns");
        }
        if (seccomp != null) {
            cmd.addAll(List.of("--seccomp", "3"));
        }
        cmd.addAll(List.of("--setenv", "PATH", "/usr/bin", "--setenv", "MALLOC_ARENA_MAX", "2",
                "--setenv", "LANG", "C.UTF-8"));
        cmd.addAll(List.of("--ro-bind", "/usr", "/usr"));
        for (String link : List.of("/lib", "/lib64", "/bin", "/sbin")) {
            Path p = Path.of(link);
            try {
                if (Files.isSymbolicLink(p)) {
                    cmd.addAll(List.of("--symlink", Files.readSymbolicLink(p).toString(), link));
                } else if (Files.isDirectory(p)) {
                    cmd.addAll(List.of("--ro-bind", link, link));
                }
            } catch (IOException e) {
                // leave it out: the program just will not see it
            }
        }
        cmd.addAll(List.of("--ro-bind-try", "/etc/ld.so.cache", "/etc/ld.so.cache"));
        cmd.addAll(List.of("--proc", "/proc", "--dev", "/dev", "--size", Long.toString(prof.scratchMiB() * 1024L * 1024),
                "--tmpfs", "/tmp", "--chdir", "/tmp"));

        Path javaHome = realPath(Path.of(System.getProperty("java.home")).toAbsolutePath());
        Set<Path> classpath = new LinkedHashSet<>();
        spec.classpath().forEach(p -> classpath.add(realPath(p)));
        classpath.addAll(runtimeClasses());
        Set<Path> visible = new LinkedHashSet<>(classpath);
        visible.add(javaHome);
        visible.addAll(externalLinks(javaHome)); // some distributions keep the JDK configuration under /etc
        for (Path p : visible) {
            if (!p.startsWith("/usr")) { // /usr is already there
                cmd.addAll(List.of("--ro-bind", p.toString(), p.toString()));
            }
        }
        List<String> cp = classpath.stream().map(Path::toString).toList();

        cmd.add(javaHome.resolve("bin/java").toString());
        cmd.addAll(List.of("-Xmx" + prof.heapMiB() + "m", "-Xms16m", "-Xss512k", "-XX:+UseSerialGC",
                "-XX:MaxMetaspaceSize=64m", "-XX:CompressedClassSpaceSize=32m", "-XX:ReservedCodeCacheSize=32m",
                "-XX:MaxDirectMemorySize=16m", "-XX:+ExitOnOutOfMemoryError", "-XX:-UsePerfData",
                "-XX:+DisableAttachMechanism", "-XX:ActiveProcessorCount=1", "-Djava.io.tmpdir=/tmp",
                "-Dfile.encoding=UTF-8", "-cp", String.join(":", cp), SandboxChild.class.getName(),
                spec.programClass()));
        cmd.addAll(spec.args());
        return cmd;
    }

    /** Targets outside the JDK (and outside /usr) of symbolic links inside its configuration directories. */
    private static Set<Path> externalLinks(Path javaHome) {
        Set<Path> out = new LinkedHashSet<>();
        try (var walk = Files.walk(javaHome, 3)) {
            walk.filter(Files::isSymbolicLink).forEach(link -> {
                try {
                    Path target = link.toRealPath();
                    if (!target.startsWith(javaHome) && !target.startsWith("/usr")) {
                        out.add(target);
                    }
                } catch (IOException ignored) {
                    // dangling link: nothing to expose
                }
            });
        } catch (IOException ignored) {
            // unreadable JDK layout: the program will fail to start rather than run with less than it needs
        }
        return out;
    }

    /** The directories or jars holding the classes the child itself needs (the bridge and the filesystem API). */
    private static List<Path> runtimeClasses() {
        try {
            Path p = Path.of(SandboxChild.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return List.of(realPath(p));
        } catch (URISyntaxException | RuntimeException e) {
            throw new SandboxUnavailableException("cannot locate the classes of the sandbox bridge");
        }
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            throw new SandboxUnavailableException("not found: " + p);
        }
    }

    // ── the bridge ─────────────────────────────────────────────────────────────

    private SandboxResult supervise(Process process, SandboxProfile prof, FileSystemService fs) {
        AtomicLong requests = new AtomicLong();
        AtomicLong io = new AtomicLong();
        AtomicBoolean overBudget = new AtomicBoolean();
        long budget = prof.maxIoMiB() * 1024L * 1024;
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        Thread stderr = Thread.ofPlatform().daemon().name("sandbox-stderr").start(() -> {
            try (var e = process.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = e.read(buf)) > 0) {
                    synchronized (err) {
                        if (err.size() < 32 * 1024) {
                            err.write(buf, 0, Math.min(n, 32 * 1024 - err.size()));
                        }
                    }
                }
            } catch (IOException ignored) {
                // process is gone
            }
        });

        Thread bridge = Thread.ofPlatform().daemon().name("sandbox-bridge").start(() -> {
            DataInputStream in = new DataInputStream(new BufferedInputStream(process.getInputStream()));
            DataOutputStream out = new DataOutputStream(process.getOutputStream());
            try {
                while (true) {
                    DataInputStream req = Rpc.readFrame(in);
                    requests.incrementAndGet();
                    byte[] reply = handle(req, fs, io);
                    io.addAndGet(reply.length);
                    if (io.get() > budget) {
                        overBudget.set(true);
                        process.destroyForcibly();
                        return;
                    }
                    Rpc.writeFrame(out, reply);
                }
            } catch (EOFException e) {
                // the program closed its end
            } catch (IOException e) {
                process.destroyForcibly(); // a malformed frame is not something to negotiate with
            }
        });

        boolean timedOut = false;
        try {
            if (!process.waitFor(prof.wallTime().toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor();
            }
            bridge.join(2000);
            stderr.join(2000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        String text;
        synchronized (err) {
            text = err.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
        return new SandboxResult(process.isAlive() ? -1 : process.exitValue(), timedOut, overBudget.get(), text,
                requests.get(), io.get());
    }

    private static byte[] handle(DataInputStream req, FileSystemService fs, AtomicLong io) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            byte op = req.readByte();
            switch (op) {
                case Rpc.MKDIR -> fs.mkdir(path(req));
                case Rpc.CREATE -> fs.createFile(path(req));
                case Rpc.UNLINK -> fs.unlink(path(req));
                case Rpc.STAT -> {
                    DirectoryEntry e = fs.stat(path(req));
                    out.writeByte(Rpc.OK);
                    Rpc.writeString(out, e.name());
                    Rpc.writeString(out, e.type().name());
                    return bytes.toByteArray();
                }
                case Rpc.READDIR -> {
                    List<DirectoryEntry> list = fs.readdir(path(req));
                    out.writeByte(Rpc.OK);
                    out.writeInt(list.size());
                    for (DirectoryEntry e : list) {
                        Rpc.writeString(out, e.name());
                        Rpc.writeString(out, e.type().name());
                    }
                    return bytes.toByteArray();
                }
                case Rpc.READ -> {
                    byte[] data = fs.readFully(path(req));
                    if (data.length > Rpc.MAX_FRAME - 64) {
                        throw new FsException(FsErrorCode.QUOTA_EXCEEDED, "file too large for the bridge");
                    }
                    out.writeByte(Rpc.OK);
                    Rpc.writeBytes(out, data);
                    io.addAndGet(data.length);
                    return bytes.toByteArray();
                }
                case Rpc.WRITE -> {
                    VfsPath p = path(req);
                    byte[] data = Rpc.readBytes(req, Rpc.MAX_FRAME);
                    io.addAndGet(data.length);
                    String id = fs.writeFully(p, data).hex();
                    out.writeByte(Rpc.OK);
                    Rpc.writeString(out, id);
                    return bytes.toByteArray();
                }
                case Rpc.HAS_CONTENT -> {
                    boolean has = fs.hasContent(path(req));
                    out.writeByte(Rpc.OK);
                    out.writeBoolean(has);
                    return bytes.toByteArray();
                }
                default -> {
                    return error(Rpc.BAD_REQUEST, "unknown operation " + op);
                }
            }
            out.writeByte(Rpc.OK);
            return bytes.toByteArray();
        } catch (FsException e) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            o.writeByte(Rpc.FS_ERROR);
            Rpc.writeString(o, e.code().name());
            Rpc.writeString(o, String.valueOf(e.getMessage()));
            return b.toByteArray();
        } catch (IllegalArgumentException e) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            o.writeByte(Rpc.FS_ERROR);
            Rpc.writeString(o, FsErrorCode.INVALID_PATH.name());
            Rpc.writeString(o, String.valueOf(e.getMessage()));
            return b.toByteArray();
        } catch (RuntimeException e) {
            return error(Rpc.BAD_REQUEST, "request failed");
        }
    }

    private static VfsPath path(DataInputStream in) throws IOException {
        return VfsPath.parse(Rpc.readString(in));
    }

    private static byte[] error(byte status, String message) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeByte(status);
        Rpc.writeString(o, message);
        return b.toByteArray();
    }
}
