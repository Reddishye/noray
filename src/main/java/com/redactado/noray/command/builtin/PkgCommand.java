package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.identity.AuthorityKeyFile;
import com.redactado.noray.identity.SigningKey;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.capability.CapabilityRegistry;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.pkg.PackageArchive;
import com.redactado.noray.pkg.PackageException;
import com.redactado.noray.pkg.PackageExchange;
import com.redactado.noray.pkg.PackageManager;
import com.redactado.noray.pkg.PackageManifest;
import com.redactado.noray.pkg.PackageRuntime;
import com.redactado.noray.pkg.PackageVerifier;
import com.redactado.noray.pkg.Publisher;
import com.redactado.noray.sandbox.Sandbox;
import com.redactado.noray.sandbox.SandboxProfile;
import com.redactado.noray.sandbox.SandboxResult;
import com.redactado.noray.sandbox.SandboxUnavailableException;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Signed packages: pin publishers, install, approve grants, activate, roll back, run; and the publisher's tools. */
public final class PkgCommand implements Command {

    @Override public String name() { return "pkg"; }
    @Override public String description() { return "Signed packages: install, approve, activate, roll back, run"; }
    @Override public String synopsis() { return "pkg list|show|install|approve|activate|rollback|run|publisher|build|sign ..."; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }
    @Override public List<String> subcommands() {
        return List.of("list", "show", "install", "approve", "activate", "deactivate", "rollback", "revoke", "remove", "verify",
                "run", "audit", "reseal", "fetch", "publisher", "keygen", "build", "sign", "inspect");
    }

    @Override
    public List<String> complete(KernelContext ctx, List<String> args) {
        if (args.size() <= 1) return subcommands();
        if (args.getFirst().equals("publisher")) return args.size() == 2 ? List.of("list", "add", "remove") : List.of();
        var pm = ctx.getServiceRegistry().get(PackageManager.class).orElse(null);
        if (pm == null) return List.of();
        if (args.size() == 2 && Set.of("show", "approve", "activate", "deactivate", "rollback", "revoke", "remove", "verify", "run")
                .contains(args.getFirst())) {
            return pm.list().stream().map(PackageManager.Installed::id).distinct().toList();
        }
        if (args.size() == 3 && Set.of("approve", "activate", "revoke", "remove", "verify").contains(args.getFirst())) {
            return pm.list().stream().filter(i -> i.id().equals(args.get(1))).map(i -> i.version().toString()).toList();
        }
        return List.of();
    }

    @Override public Set<String> switchFlags() {
        return Set.of("allow-downgrade", "no-activate", "trusted", "no-passphrase", "verify");
    }

    @Override
    public String longHelp() {
        return """
            Packages are signed by publishers you pin, installed without running, and run only with the grants you
            approve for one exact version. Nothing is fetched from anywhere and nothing updates itself.

            Operator:
              pkg publisher add NAME KEY[,KEY...] [--threshold N] [--trusted]
                                              pin a publisher (k-of-n keys); --trusted allows in-JVM packages
              pkg publisher list | remove NAME
              pkg fetch PEER SHA256           get a package from one of your own nodes by payload hash, then install it
                                              (verified here, not approved; no registry involved)
              pkg install FILE [--allow-downgrade]
                                              verify and store a .nrpkg (FILE may be vfs:/mnt/... on the Noray filesystem);
                                              shows what it requests, does NOT run it
              pkg approve ID VERSION [--grants G] [--no-activate]
                                              approve the requested grants, or a narrower list G (comma separated,
                                              --grants= for none), and make that version active
              pkg activate ID VERSION | deactivate ID | rollback ID
              pkg revoke ID VERSION           withdraw an approval      pkg remove ID VERSION   delete a stored version
              pkg list | show ID | verify ID [VERSION] | verify FILE
              pkg run ID [ARGS...]            run the active version of a process package in the sandbox
              pkg audit [N] [--verify]        the last N audit entries; --verify checks the hash chain
              pkg reseal                      after reviewing `pkg list`, accept approvals that failed the integrity
                                              check (edited outside Noray, or first start with a keyring)
            JVM packages load on the running node as soon as they are approved or activated; process packages run on demand.

            Publisher:
              pkg keygen FILE [--passphrase-file F | --no-passphrase]
              pkg build --id ID --version V --main CLASS --payload JAR --publisher NAME --out FILE
                        [--sandbox process|jvm] [--requests G] [--depends ID@SHA256,...] [--description TEXT] [--api N]
              pkg sign FILE --key KEYFILE [--passphrase-file F]   (each co-signer runs it once)
              pkg inspect FILE                print the manifest without checking trust
            """;
    }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        PrintStream out = inv.out();
        List<String> a = inv.args();
        if (a.isEmpty()) {
            out.println(longHelp());
            return 1;
        }
        try {
            return switch (a.get(0)) {
                case "keygen" -> keygen(inv);
                case "build" -> build(inv);
                case "sign" -> sign(inv);
                case "inspect" -> inspect(inv);
                default -> operator(inv, manager(inv));
            };
        } catch (PackageException e) {
            inv.err().println("pkg: " + e.getMessage());
            return 1;
        }
    }

    private static PackageManager manager(CommandInvocation inv) {
        return inv.context().getServiceRegistry().get(PackageManager.class).orElseThrow(() ->
                new PackageException(PackageException.Reason.NOT_FOUND, "the package manager is not running"));
    }

    private int operator(CommandInvocation inv, PackageManager pm) throws Exception {
        PrintStream out = inv.out();
        List<String> a = inv.args();
        switch (a.get(0)) {
            case "publisher" -> {
                return publisher(inv, pm);
            }
            case "install" -> {
                need(a, 2, "pkg install FILE");
                PackageArchive archive = PackageArchive.read(readSource(inv, a.get(1)));
                PackageManifest m = pm.install(archive, inv.hasFlag("allow-downgrade"));
                out.println("installed " + m + " from publisher " + m.publisher() + " (" + m.sandbox().text() + ")");
                out.println("requests: " + (m.requests().isEmpty() ? "(nothing)" : String.join(", ", m.requests())));
                pm.activeVersion(m.id()).filter(v -> !v.equals(m.version().toString())).ifPresent(v -> {
                    var current = pm.list().stream().filter(i -> i.id().equals(m.id()) && i.active()).findFirst();
                    current.ifPresent(c -> {
                        List<String> added = new ArrayList<>(m.requests());
                        added.removeAll(c.grants());
                        if (!added.isEmpty()) out.println("NEW compared to active " + v + ": " + String.join(", ", added));
                    });
                });
                out.println("not running; review, then: pkg approve " + m.id() + " " + m.version());
                return 0;
            }
            case "approve" -> {
                need(a, 3, "pkg approve ID VERSION [--grants G]");
                String grants = inv.flags().containsKey("grants") ? inv.getFlag("grants", "") : null;
                List<String> g = pm.approve(a.get(1), a.get(2), grants, !inv.hasFlag("no-activate"));
                out.println("approved " + a.get(1) + " " + a.get(2) + " with: " + (g.isEmpty() ? "(nothing)" : String.join(", ", g)));
                if (!inv.hasFlag("no-activate")) applyNow(inv, a.get(1));
                return 0;
            }
            case "activate" -> {
                need(a, 3, "pkg activate ID VERSION");
                pm.activate(a.get(1), a.get(2));
                out.println(a.get(1) + " " + a.get(2) + " is active");
                applyNow(inv, a.get(1));
                return 0;
            }
            case "deactivate" -> {
                need(a, 2, "pkg deactivate ID");
                pm.deactivate(a.get(1));
                out.println(a.get(1) + " has no active version");
                applyNow(inv, a.get(1));
                return 0;
            }
            case "rollback" -> {
                need(a, 2, "pkg rollback ID");
                out.println(a.get(1) + " " + pm.rollback(a.get(1)) + " is active again");
                applyNow(inv, a.get(1));
                return 0;
            }
            case "revoke" -> {
                need(a, 3, "pkg revoke ID VERSION");
                pm.revoke(a.get(1), a.get(2));
                out.println("approval withdrawn");
                return 0;
            }
            case "remove" -> {
                need(a, 3, "pkg remove ID VERSION");
                pm.remove(a.get(1), a.get(2));
                out.println("removed " + a.get(1) + " " + a.get(2));
                return 0;
            }
            case "list" -> {
                var all = pm.list();
                if (all.isEmpty()) out.println("(no packages installed)");
                for (var i : all) {
                    out.printf("%-36s %-12s %-8s %-10s %s%n", i.id(), i.version(), i.manifest().sandbox().text(),
                            i.manifest().publisher(), i.active() ? "ACTIVE" : i.approved() ? "approved" : "installed");
                }
                return 0;
            }
            case "show" -> {
                need(a, 2, "pkg show ID");
                boolean any = false;
                for (var i : pm.list()) {
                    if (!i.id().equals(a.get(1))) continue;
                    any = true;
                    PackageManifest m = i.manifest();
                    out.println(m + (i.active() ? "  [active]" : ""));
                    out.println("  publisher  " + m.publisher() + "  (" + m.signatures().size() + " signature(s))");
                    out.println("  sandbox    " + m.sandbox().text() + "   main " + m.main() + "   api " + m.api());
                    out.println("  payload    " + m.payloadSha256() + "  " + m.payloadSize() + " bytes");
                    if (!m.description().isEmpty()) out.println("  about      " + m.description());
                    out.println("  requests   " + (m.requests().isEmpty() ? "(nothing)" : String.join(", ", m.requests())));
                    out.println("  approved   " + (i.approved() ? (i.grants().isEmpty() ? "(nothing)" : String.join(", ", i.grants())) : "no"));
                    if (!m.depends().isEmpty()) out.println("  depends    " + m.depends());
                }
                if (!any) {
                    out.println("pkg: " + a.get(1) + " is not installed");
                    return 1;
                }
                return 0;
            }
            case "verify" -> {
                need(a, 2, "pkg verify ID [VERSION] | pkg verify FILE");
                if (a.get(1).endsWith(".nrpkg") || a.get(1).startsWith("vfs:")) {
                    PackageArchive archive = PackageArchive.read(readSource(inv, a.get(1)));
                    var v = PackageVerifier.verify(archive.manifest(), archive.payload(), pm.publishers());
                    out.println("OK " + v.manifest() + " signed by " + v.publisher().name());
                    return 0;
                }
                List<String> versions = a.size() > 2 ? List.of(a.get(2))
                        : pm.list().stream().filter(i -> i.id().equals(a.get(1))).map(i -> i.version().toString()).toList();
                if (versions.isEmpty()) {
                    out.println("pkg: " + a.get(1) + " is not installed");
                    return 1;
                }
                int bad = 0;
                for (String v : versions) {
                    try {
                        out.println("OK " + pm.verifyInstalled(a.get(1), v));
                    } catch (PackageException e) {
                        bad++;
                        out.println("FAIL " + a.get(1) + " " + v + ": " + e.getMessage());
                    }
                }
                return bad == 0 ? 0 : 1;
            }
            case "run" -> {
                need(a, 2, "pkg run ID [ARGS...]");
                FileSystemService fs = inv.context().getServiceRegistry().get(FileSystemService.class).orElse(null);
                if (fs == null) {
                    out.println("pkg: the filesystem service is not available");
                    return 1;
                }
                CapabilityRegistry reg = inv.context().getServiceRegistry().get(CapabilityRegistry.class).orElse(null);
                try {
                    SandboxResult r = pm.run(a.get(1), a.subList(2, a.size()), fs, reg, SandboxProfile.defaults(), Sandbox.detect());
                    out.printf("exit %d (%s); %d filesystem calls, %d bytes%n", r.exitCode(), r.limitHit().describe(), r.requests(), r.ioBytes());
                    if (!r.stderr().isBlank()) out.println(r.stderr());
                    return r.ok() ? 0 : 1;
                } catch (SandboxUnavailableException e) {
                    out.println("pkg: the process sandbox is NOT available, nothing was run: " + e.getMessage());
                    return 1;
                }
            }
            case "fetch" -> {
                need(a, 3, "pkg fetch PEER SHA256");
                var ex = inv.context().getServiceRegistry().get(PackageExchange.class).orElse(null);
                if (ex == null) {
                    out.println("pkg: the mesh is not running");
                    return 1;
                }
                var got = ex.fetch(new NodeId(a.get(1)), a.get(2), Duration.ofSeconds(60));
                if (got.isEmpty()) {
                    out.println("pkg: " + a.get(1) + " does not have a package with payload " + a.get(2));
                    return 1;
                }
                PackageManifest m = pm.install(got.get(), inv.hasFlag("allow-downgrade"));
                out.println("fetched and installed " + m + " (verified against this node's pinned publishers; not approved)");
                return 0;
            }
            case "reseal" -> {
                if (pm.integrityProblem() != null) out.println("accepting the current state after: " + pm.integrityProblem());
                pm.reseal();
                out.println("package approvals and publishers sealed");
                return 0;
            }
            case "audit" -> {
                if (inv.hasFlag("verify")) {
                    long broken = pm.audit().verify();
                    out.println(broken < 0 ? "audit chain intact" : "audit chain BROKEN at line " + broken);
                    return broken < 0 ? 0 : 1;
                }
                int n = a.size() > 1 ? Integer.parseInt(a.get(1)) : 20;
                pm.audit().tail(n).forEach(out::println);
                return 0;
            }
            default -> {
                out.println(longHelp());
                return 1;
            }
        }
    }

    /** JVM packages take effect at once on a running node (process packages run on demand anyway). */
    private static void applyNow(CommandInvocation inv, String id) {
        inv.context().getServiceRegistry().get(PackageRuntime.class).ifPresentOrElse(
                rt -> {
                    try {
                        inv.out().println("running node: " + rt.apply(id));
                    } catch (PackageException e) {
                        inv.out().println("running node: " + e.getMessage() + " (the change is saved; it applies at the next start)");
                    }
                },
                () -> inv.out().println("JVM packages apply at the next start"));
    }

    private int publisher(CommandInvocation inv, PackageManager pm) {
        PrintStream out = inv.out();
        List<String> a = inv.args();
        String action = a.size() > 1 ? a.get(1) : "list";
        switch (action) {
            case "list" -> {
                if (pm.publishers().all().isEmpty()) out.println("(no publishers pinned)");
                for (Publisher p : pm.publishers().all()) {
                    out.println(p.name() + "  " + p.threshold() + "-of-" + p.keys().size() + (p.trusted() ? "  TRUSTED (in-JVM allowed)" : ""));
                    p.keysHex().forEach(k -> out.println("    " + k));
                }
                return 0;
            }
            case "add" -> {
                need(a, 4, "pkg publisher add NAME KEY[,KEY...] [--threshold N] [--trusted]");
                List<byte[]> keys = new ArrayList<>();
                for (String k : a.get(3).split(",")) {
                    if (!k.matches("[0-9a-fA-F]{64}")) {
                        out.println("pkg: not a public key (64 hex characters): " + k);
                        return 1;
                    }
                    keys.add(HexFormat.of().parseHex(k.toLowerCase()));
                }
                int threshold = Integer.parseInt(inv.getFlag("threshold", "1"));
                Publisher p;
                try {
                    p = new Publisher(a.get(2), keys, threshold, inv.hasFlag("trusted"));
                } catch (IllegalArgumentException e) {
                    out.println("pkg: " + e.getMessage());
                    return 1;
                }
                pm.pinPublisher(p);
                out.println("pinned " + p.name() + " (" + p.threshold() + "-of-" + p.keys().size()
                        + (p.trusted() ? ", trusted: its packages may run inside the node" : ", process sandbox only") + ")");
                return 0;
            }
            case "remove" -> {
                need(a, 3, "pkg publisher remove NAME");
                boolean had = pm.unpinPublisher(a.get(2));
                out.println(had ? "unpinned " + a.get(2) + "; its packages stop loading at the next verification"
                        : "pkg: no publisher " + a.get(2));
                return had ? 0 : 1;
            }
            default -> {
                out.println("pkg publisher list|add|remove");
                return 1;
            }
        }
    }

    // ── publisher tools ───────────────────────────────────────────────────────────────────────────────────────────────

    private int keygen(CommandInvocation inv) throws Exception {
        PrintStream out = inv.out();
        need(inv.args(), 2, "pkg keygen FILE");
        Path file = Path.of(inv.args().get(1));
        if (Files.exists(file)) {
            out.println("pkg: " + file + " already exists; refusing to overwrite a key");
            return 1;
        }
        char[] pass = passphrase(inv);
        boolean plain = inv.hasFlag("no-passphrase");
        if (pass == null && !plain) {
            out.println("pkg: protect the signing key: --passphrase-file FILE (or NORAY_AUTHORITY_PASSPHRASE), or --no-passphrase");
            return 1;
        }
        SigningKey k = SigningKey.generate();
        AuthorityKeyFile.write(file, k, plain ? null : pass);
        out.println("public key: " + k.publicKeyHex());
        out.println("operators pin it with: pkg publisher add <name> " + k.publicKeyHex());
        return 0;
    }

    private int build(CommandInvocation inv) throws Exception {
        PrintStream out = inv.out();
        for (String f : List.of("id", "version", "main", "payload", "publisher", "out")) {
            if (!inv.flags().containsKey(f)) {
                out.println("pkg build: --" + f + " is required");
                return 1;
            }
        }
        byte[] payload = Files.readAllBytes(Path.of(inv.getFlag("payload", "")));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        var draft = new PackageManifest.Draft(inv.getFlag("id", ""), inv.getFlag("version", ""),
                Integer.parseInt(inv.getFlag("api", String.valueOf(PackageManifest.NODE_API))),
                "jvm".equals(inv.getFlag("sandbox", "process")) ? PackageManifest.Sandboxing.JVM : PackageManifest.Sandboxing.PROCESS,
                inv.getFlag("main", ""), hash, payload.length, inv.getFlag("publisher", ""),
                splitList(inv.getFlag("requests", "")), splitList(inv.getFlag("depends", "")), inv.getFlag("description", ""));
        Path target = Path.of(inv.getFlag("out", ""));
        try (OutputStream os = Files.newOutputStream(target)) {
            new PackageArchive(draft.toBytes(), payload).write(os);
        }
        out.println("wrote unsigned " + target + " (payload " + hash + "); sign it with: pkg sign " + target + " --key KEYFILE");
        return 0;
    }

    private int sign(CommandInvocation inv) throws Exception {
        PrintStream out = inv.out();
        need(inv.args(), 2, "pkg sign FILE --key KEYFILE");
        if (!inv.flags().containsKey("key")) {
            out.println("pkg sign: --key KEYFILE is required");
            return 1;
        }
        Path file = Path.of(inv.args().get(1));
        PackageArchive archive = PackageArchive.read(readSource(inv, file.toString()));
        PackageManifest m = PackageManifest.parse(archive.manifest());
        SigningKey key = AuthorityKeyFile.read(Path.of(inv.getFlag("key", "")), passphrase(inv));
        try {
            byte[] sig = key.sign(PackageManifest.SIGNATURE_DOMAIN, m.body());
            byte[] signed = m.withSignature(key.publicKey(), sig);
            byte[] bytes = new PackageArchive(signed, archive.payload()).toBytes();
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            out.println("signed " + m + " with " + key.publicKeyHex() + " (" + PackageManifest.parse(signed).signatures().size()
                    + " signature(s))");
        } finally {
            key.destroy();
        }
        return 0;
    }

    private int inspect(CommandInvocation inv) throws Exception {
        need(inv.args(), 2, "pkg inspect FILE");
        PackageArchive archive = PackageArchive.read(readSource(inv, inv.args().get(1)));
        inv.out().print(new String(archive.manifest(), StandardCharsets.UTF_8));
        PackageManifest.parse(archive.manifest()); // refuse loudly if it would be refused on install
        return 0;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private static byte[] readSource(CommandInvocation inv, String source) throws Exception {
        if (source.startsWith("vfs:")) {
            FileSystemService fs = inv.context().getServiceRegistry().get(FileSystemService.class).orElseThrow(() ->
                    new PackageException(PackageException.Reason.NOT_FOUND, "the filesystem service is not available"));
            return fs.readFully(VfsPath.parse(source.substring(4)));
        }
        Path file = Path.of(source);
        if (Files.size(file) > PackageManifest.MAX_PAYLOAD + PackageManifest.MAX_BYTES + 64) {
            throw new PackageException(PackageException.Reason.MALFORMED, source + " is larger than any valid package");
        }
        return Files.readAllBytes(file);
    }

    private static List<String> splitList(String s) {
        return s.isBlank() ? List.of() : Arrays.stream(s.split(",")).map(String::strip).toList();
    }

    private static void need(List<String> args, int n, String usage) {
        if (args.size() < n) {
            throw new PackageException(PackageException.Reason.MALFORMED, "usage: " + usage);
        }
    }

    private static char[] passphrase(CommandInvocation inv) throws Exception {
        String file = inv.getFlag("passphrase-file", "");
        if (!file.isEmpty()) {
            return Files.readString(Path.of(file)).stripTrailing().toCharArray();
        }
        String env = System.getenv("NORAY_AUTHORITY_PASSPHRASE");
        return env == null || env.isEmpty() ? null : env.toCharArray();
    }
}
