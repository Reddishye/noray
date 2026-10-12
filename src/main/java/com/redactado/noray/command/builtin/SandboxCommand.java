package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.command.CommandRegistry;
import com.redactado.noray.command.Completions;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.capability.ScopedFileSystemService;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.sandbox.Sandbox;
import com.redactado.noray.sandbox.SandboxProfile;
import com.redactado.noray.sandbox.SandboxResult;
import com.redactado.noray.sandbox.SandboxSpec;
import com.redactado.noray.sandbox.SandboxUnavailableException;
import com.redactado.noray.web.ui.Json;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Runs a program from a jar in the OS-level sandbox. */
public final class SandboxCommand implements Command {

    @Override public String name() { return "sandbox"; }
    @Override public String description() { return "Run untrusted code in an isolated process"; }
    @Override public String synopsis() { return "sandbox status | sandbox run [options] JAR CLASS [ARGS...]"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }
    @Override public List<String> subcommands() { return List.of("status", "run"); }

    @Override
    public List<String> complete(KernelContext ctx, List<String> args) {
        if (args.size() <= 1) return subcommands();
        String prev = args.get(args.size() - 2);
        if (prev.equals("--read") || prev.equals("--write")) {
            return Completions.vfsPaths(ctx, args.getLast(), true);
        }
        return args.getLast().startsWith("--")
                ? List.of("--read", "--write", "--heap", "--cpu", "--time", "--io", "--json") : List.of();
    }

    @Override
    public String longHelp() {
        return """
            Usage: sandbox status [--json]
                   sandbox run [--read PATH]... [--write PATH]... [--heap MiB] [--cpu SECONDS] [--time SECONDS]
                               [--io MiB] [--json] JAR CLASS [ARGS...]

            CLASS must implement com.redactado.noray.sandbox.SandboxedProgram. It runs in a separate process with no
            network, no view of the node's files, keys or environment, no capabilities and a seccomp filter. Its only
            way out is the filesystem, limited to the --read / --write subtrees (quotas and rules of the namespace
            apply). If the system cannot provide this isolation, nothing runs.
            """;
    }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        var out = inv.out();
        // options repeat (--read A --read B) and must stay in order, so read them from the raw words, not the flag map
        var args = new ArrayList<>(CommandRegistry.parseTokens(inv.rawCommandLine()));
        if (!args.isEmpty()) args.remove(0);
        boolean json = args.remove("--json");
        if (args.isEmpty()) {
            out.println(longHelp());
            return 1;
        }
        if (args.get(0).equals("status")) {
            try {
                Sandbox s = Sandbox.detect();
                if (json) {
                    out.println("{\"available\":true,\"cgroupLimits\":" + s.hasCgroupLimits() + "}");
                    return 0;
                }
                out.println("sandbox: available (namespaces, no network, empty root, seccomp"
                        + (s.hasCgroupLimits() ? ", cgroup limits" : ", no cgroup limits") + ")");
                return 0;
            } catch (SandboxUnavailableException e) {
                out.println(json ? "{\"available\":false,\"reason\":" + Json.quote(e.getMessage()) + "}"
                        : "sandbox: NOT available: " + e.getMessage());
                return 1;
            }
        }
        if (!args.get(0).equals("run")) {
            out.println(longHelp());
            return 1;
        }
        args.remove(0);
        List<String> read = new ArrayList<>();
        List<String> write = new ArrayList<>();
        SandboxProfile profile = SandboxProfile.defaults();
        try {
            while (!args.isEmpty() && args.get(0).startsWith("--")) {
                String opt = args.remove(0);
                if (args.isEmpty()) {
                    out.println("sandbox: " + opt + " needs a value");
                    return 1;
                }
                String v = args.remove(0);
                switch (opt) {
                    case "--read" -> read.add(v);
                    case "--write" -> write.add(v);
                    case "--heap" -> profile = profile.withHeapMiB(Integer.parseInt(v));
                    case "--cpu" -> profile = profile.withCpuSeconds(Integer.parseInt(v));
                    case "--time" -> profile = profile.withWallTime(Duration.ofSeconds(Long.parseLong(v)));
                    case "--io" -> profile = profile.withMaxIoMiB(Integer.parseInt(v));
                    default -> {
                        out.println("sandbox: unknown option " + opt);
                        return 1;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            out.println("sandbox: invalid option value: " + e.getMessage());
            return 1;
        }
        if (args.size() < 2) {
            out.println(longHelp());
            return 1;
        }
        FileSystemService base = inv.context().getServiceRegistry().get(FileSystemService.class).orElse(null);
        if (base == null) {
            out.println("sandbox: the filesystem service is not available");
            return 1;
        }
        Path jar = Path.of(args.get(0)).toAbsolutePath();
        FileSystemService scoped = new ScopedFileSystemService(base, read, write, "sandbox", null);
        try {
            SandboxResult r = Sandbox.detect().run(
                    new SandboxSpec(List.of(jar), args.get(1), args.subList(2, args.size()), profile), scoped);
            if (json) {
                out.println("{\"exit\":" + r.exitCode() + ",\"limit\":\"" + r.limitHit() + "\",\"ok\":" + r.ok()
                        + ",\"requests\":" + r.requests() + ",\"ioBytes\":" + r.ioBytes() + ",\"stderr\":"
                        + Json.quote(r.stderr()) + "}");
                return r.ok() ? 0 : 1;
            }
            out.printf("exit %d (%s); %d filesystem calls, %d bytes%n", r.exitCode(), r.limitHit().describe(), r.requests(), r.ioBytes());
            if (!r.stderr().isBlank()) {
                out.println(r.stderr());
            }
            return r.ok() ? 0 : 1;
        } catch (SandboxUnavailableException e) {
            out.println("sandbox: NOT available, nothing was run: " + e.getMessage());
            return 1;
        }
    }
}
