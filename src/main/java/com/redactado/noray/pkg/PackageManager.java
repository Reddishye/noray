package com.redactado.noray.pkg;

import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.capability.ScopedFileSystemService;
import com.redactado.noray.kernel.capability.Capability;
import com.redactado.noray.kernel.capability.CapabilityRegistry;
import com.redactado.noray.kernel.capability.CapabilitySet;
import com.redactado.noray.pkg.PackageException.Reason;
import com.redactado.noray.sandbox.Sandbox;
import com.redactado.noray.sandbox.SandboxProfile;
import com.redactado.noray.sandbox.SandboxResult;
import com.redactado.noray.sandbox.SandboxSpec;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Installs, approves, activates and runs packages. Nothing runs at install time: a package is stored, then the operator
 * approves the grants for one exact version (possibly narrower than requested), which also makes it the active version.
 * Versions are immutable and kept side by side; {@link #rollback} returns to the previously active one.
 *
 * <pre>
 * &lt;data&gt;/packages/
 *   publishers.properties        pinned publishers
 *   state.properties             approvals, active versions, activation history
 *   audit.log                    hash-chained record of every action
 *   store/&lt;id&gt;/&lt;version&gt;/package.manifest, payload.jar
 * </pre>
 *
 * Every load re-verifies signature and payload hash against the publishers pinned at that moment.
 */
public final class PackageManager {

    private static final System.Logger LOG = System.getLogger("noray.pkg");

    /** One installed version and what the operator decided about it. */
    public record Installed(PackageManifest manifest, boolean approved, List<String> grants, boolean active) {
        public String id() { return manifest.id(); }
        public Version version() { return manifest.version(); }
    }

    /** A verified package ready to load: its payload and those of its dependencies, re-read and re-hashed. */
    public record Loadable(PackageManifest manifest, Publisher publisher, byte[] payload, List<byte[]> dependencyPayloads,
                           CapabilitySet grants) {
    }

    private static final List<String> SEALED_FILES = List.of("state.properties", "publishers.properties");

    private final Path root;
    private final PublisherRegistry publishers;
    private final PackageAudit audit;
    private final Properties state = new Properties();
    private final byte[] macKey;
    private volatile String integrityProblem;

    public PackageManager(Path root) {
        this(root, null);
    }

    /**
     * @param macKey when given (a keyring subkey), approvals and pinned publishers are authenticated: a file edited
     *               outside Noray (or a missing seal) stops every package from loading until {@link #reseal()}
     */
    public PackageManager(Path root, byte[] macKey) {
        this.root = root;
        this.macKey = macKey == null ? null : macKey.clone();
        try {
            Files.createDirectories(root.resolve("store"));
            StoreFiles.restrict(root);
            Path s = root.resolve("state.properties");
            if (Files.exists(s)) {
                state.load(new StringReader(Files.readString(s, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new PackageException(Reason.IO, "cannot open package store " + root, e);
        }
        this.publishers = new PublisherRegistry(root.resolve("publishers.properties"));
        this.audit = new PackageAudit(root.resolve("audit.log"));
        checkSeals();
    }

    // ── integrity of approvals (PK1) ──────────────────────────────────────────────────────────────────────────────────

    private void checkSeals() {
        if (macKey == null) return;
        for (String f : SEALED_FILES) {
            Path file = root.resolve(f);
            Path seal = root.resolve(f + ".mac");
            if (!Files.exists(file)) continue;
            try {
                byte[] expected = mac(Files.readAllBytes(file));
                byte[] found = Files.exists(seal) ? HexFormat.of().parseHex(Files.readString(seal).strip()) : new byte[0];
                if (!MessageDigest.isEqual(expected, found)) {
                    integrityProblem = f + (found.length == 0 ? " has no seal" : " was changed outside Noray");
                }
            } catch (IOException | IllegalArgumentException e) {
                integrityProblem = f + " cannot be checked: " + e.getMessage();
            }
        }
        if (integrityProblem != null) {
            LOG.log(System.Logger.Level.ERROR, "package state failed its integrity check (" + integrityProblem
                    + "): no package will load until an operator reviews `pkg list` and runs `pkg reseal`");
            audit.record("integrity-failed", "packages", integrityProblem);
        }
    }

    /** Null when the approvals are intact (or not sealed at all), else what is wrong. */
    public String integrityProblem() {
        return integrityProblem;
    }

    /** Accepts the current approvals and publishers as reviewed and seals them again. */
    public synchronized void reseal() {
        if (macKey == null) throw new PackageException(Reason.CONFLICT, "package state is not sealed (no keyring)");
        String was = integrityProblem;
        integrityProblem = null;
        seal();
        audit.record("reseal", "packages", was == null ? "" : "accepted after: " + was);
    }

    private void seal() {
        if (macKey == null || integrityProblem != null) return;
        for (String f : SEALED_FILES) {
            Path file = root.resolve(f);
            if (!Files.exists(file)) continue;
            try {
                StoreFiles.writeAtomically(root.resolve(f + ".mac"),
                        HexFormat.of().formatHex(mac(Files.readAllBytes(file))).getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                throw new PackageException(Reason.IO, "cannot seal " + f, e);
            }
        }
    }

    private byte[] mac(byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(macKey, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireIntact() {
        if (integrityProblem != null) {
            throw new PackageException(Reason.CONFLICT, "package state failed its integrity check (" + integrityProblem
                    + "); review `pkg list`, then `pkg reseal`");
        }
    }

    public Path root() { return root; }
    public PublisherRegistry publishers() { return publishers; }
    public PackageAudit audit() { return audit; }

    // ── publishers ────────────────────────────────────────────────────────────────────────────────────────────────────

    public synchronized void pinPublisher(Publisher p) {
        requireIntact();
        publishers.put(p);
        seal();
        audit.record("publisher-pin", p.name(), "keys=" + String.join(",", p.keysHex()) + " threshold=" + p.threshold()
                + " trusted=" + p.trusted());
    }

    public synchronized boolean unpinPublisher(String name) {
        requireIntact();
        boolean had = publishers.remove(name);
        if (had) seal();
        if (had) audit.record("publisher-unpin", name, "");
        return had;
    }

    // ── install ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Verifies and stores a package without running anything. Installing the identical package twice is a no-op;
     * the same version with different content is a conflict (versions are immutable); a version lower than one
     * already installed is a downgrade and needs {@code allowDowngrade}.
     */
    public synchronized PackageManifest install(PackageArchive archive, boolean allowDowngrade) {
        PackageManifest m;
        try {
            m = PackageVerifier.verify(archive.manifest(), archive.payload(), publishers).manifest();
        } catch (PackageException e) {
            audit.record("install-refused", safeId(archive), e.getMessage());
            throw e;
        }
        Path dir = versionDir(m.id(), m.version().toString());
        if (Files.exists(dir.resolve("package.manifest"))) {
            byte[] existing = read(dir.resolve("package.manifest"));
            if (Arrays.equals(existing, archive.manifest())) {
                return m;
            }
            PackageManifest old = PackageManifest.parse(existing);
            if (!old.payloadSha256().equals(m.payloadSha256()) || !old.requests().equals(m.requests())
                    || !Arrays.equals(old.body(), m.body())) {
                audit.record("install-refused", m.toString(), "same version, different content");
                throw new PackageException(Reason.CONFLICT, m + " is already installed with different content; "
                        + "versions are immutable, publish a new version");
            }
            // same body, different set of signatures (e.g. one more co-signer): keep the newer manifest
        }
        Optional<Version> newest = versions(m.id()).stream().max(Comparator.naturalOrder());
        if (newest.isPresent() && m.version().compareTo(newest.get()) < 0 && !allowDowngrade) {
            audit.record("install-refused", m.toString(), "downgrade from " + newest.get());
            throw new PackageException(Reason.DOWNGRADE, m + " is older than the installed " + newest.get()
                    + " (use --allow-downgrade, or pkg activate to return to a version that is still installed)");
        }
        try {
            Files.createDirectories(dir);
            StoreFiles.writeAtomically(dir.resolve("payload.jar"), archive.payload());
            StoreFiles.writeAtomically(dir.resolve("package.manifest"), archive.manifest()); // written last: marks complete
        } catch (IOException e) {
            throw new PackageException(Reason.IO, "cannot store " + m, e);
        }
        audit.record("install", m.toString(), "publisher=" + m.publisher() + " sandbox=" + m.sandbox().text()
                + " payload=" + m.payloadSha256() + " requests=" + String.join(",", m.requests()));
        return m;
    }

    // ── approval and activation ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * Approves one version with the given grants ({@code null} = everything it requested). Each approved grant must be
     * covered by a request: the operator can narrow, never widen. Dependencies must be installed with the exact payload
     * hash and approved. With {@code activate}, the version also becomes the active one.
     */
    public synchronized List<String> approve(String id, String version, String grants, boolean activate) {
        requireIntact();
        PackageManifest m = verifiedManifest(id, version);
        List<String> approved = new ArrayList<>();
        if (grants == null) {
            approved.addAll(m.requests());
        } else if (!grants.isBlank()) {
            CapabilitySet requested = CapabilitySet.parse(String.join(",", m.requests()));
            for (Capability c : parseGrants(grants).list()) {
                if (!requested.allows(c)) {
                    audit.record("approve-refused", m.toString(), "grant " + c + " exceeds the request");
                    throw new PackageException(Reason.GRANT_EXCEEDS_REQUEST, c + " is not covered by what " + m
                            + " requested (" + String.join(",", m.requests()) + ")");
                }
                approved.add(c.toString());
            }
        }
        checkDependencies(m);
        state.setProperty(approvalKey(id, version), String.join(",", approved));
        audit.record("approve", m.toString(), "grants=" + String.join(",", approved));
        if (activate) {
            activateLocked(id, version);
        } else {
            save();
        }
        return List.copyOf(approved);
    }

    public synchronized void activate(String id, String version) {
        activateLocked(id, version);
    }

    private void activateLocked(String id, String version) {
        requireIntact();
        PackageManifest m = verifiedManifest(id, version);
        if (state.getProperty(approvalKey(id, version)) == null) {
            throw new PackageException(Reason.NOT_FOUND, m + " is not approved (pkg approve " + id + " " + version + ")");
        }
        checkDependencies(m);
        String current = state.getProperty(activeKey(id));
        if (version.equals(current)) {
            save();
            return;
        }
        if (current != null) {
            List<String> history = history(id);
            history.remove(current);
            history.add(current);
            state.setProperty(historyKey(id), String.join(",", history));
        }
        state.setProperty(activeKey(id), version);
        save();
        audit.record("activate", m.toString(), current == null ? "" : "was " + current);
    }

    /** Makes the previously active version active again. */
    public synchronized String rollback(String id) {
        requireIntact();
        List<String> history = history(id);
        String current = state.getProperty(activeKey(id));
        while (!history.isEmpty()) {
            String previous = history.removeLast();
            if (previous.equals(current) || state.getProperty(approvalKey(id, previous)) == null
                    || !Files.exists(versionDir(id, previous).resolve("package.manifest"))) {
                continue; // removed or revoked since
            }
            state.setProperty(historyKey(id), String.join(",", history));
            state.setProperty(activeKey(id), previous);
            save();
            audit.record("rollback", id + " " + previous, current == null ? "" : "was " + current);
            return previous;
        }
        throw new PackageException(Reason.NOT_FOUND, id + " has no earlier approved version to return to");
    }

    public synchronized void deactivate(String id) {
        String current = (String) state.remove(activeKey(id));
        if (current == null) {
            throw new PackageException(Reason.NOT_FOUND, id + " has no active version");
        }
        save();
        audit.record("deactivate", id + " " + current, "");
    }

    public synchronized void revoke(String id, String version) {
        if (version.equals(state.getProperty(activeKey(id)))) {
            throw new PackageException(Reason.CONFLICT, id + " " + version + " is active; deactivate it first");
        }
        if (state.remove(approvalKey(id, version)) == null) {
            throw new PackageException(Reason.NOT_FOUND, id + " " + version + " is not approved");
        }
        save();
        audit.record("revoke", id + " " + version, "");
    }

    public synchronized void remove(String id, String version) {
        if (version.equals(state.getProperty(activeKey(id)))) {
            throw new PackageException(Reason.CONFLICT, id + " " + version + " is active; deactivate it or activate another");
        }
        for (Installed other : list()) {
            if (other.active() && other.manifest().depends().stream().anyMatch(d -> d.id().equals(id)
                    && d.payloadSha256().equals(manifestOf(id, version).payloadSha256()))) {
                throw new PackageException(Reason.CONFLICT, other.manifest() + " depends on " + id + " " + version);
            }
        }
        Path dir = versionDir(id, version);
        if (!Files.exists(dir)) {
            throw new PackageException(Reason.NOT_FOUND, id + " " + version + " is not installed");
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            Path idDir = dir.getParent();
            try (Stream<Path> rest = Files.list(idDir)) {
                if (rest.findAny().isEmpty()) Files.delete(idDir);
            }
        } catch (IOException e) {
            throw new PackageException(Reason.IO, "cannot remove " + dir, e);
        }
        state.remove(approvalKey(id, version));
        List<String> history = history(id);
        if (history.remove(version)) state.setProperty(historyKey(id), String.join(",", history));
        save();
        audit.record("remove", id + " " + version, "");
    }

    // ── queries ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Every installed version, sorted by id then version. Damaged entries are listed in the log and skipped. */
    public synchronized List<Installed> list() {
        List<Installed> out = new ArrayList<>();
        for (String id : ids()) {
            for (Version v : versions(id).stream().sorted().toList()) {
                try {
                    PackageManifest m = manifestOf(id, v.toString());
                    String grants = state.getProperty(approvalKey(id, v.toString()));
                    out.add(new Installed(m, grants != null, splitGrants(grants), v.toString().equals(state.getProperty(activeKey(id)))));
                } catch (PackageException e) {
                    LOG.log(System.Logger.Level.WARNING, "package store entry " + id + " " + v + " is damaged: " + e.getMessage());
                }
            }
        }
        return out;
    }

    public synchronized Optional<String> activeVersion(String id) {
        return Optional.ofNullable(state.getProperty(activeKey(id)));
    }

    /** Re-verifies a stored version (signature by a currently pinned publisher, payload hash). */
    public synchronized PackageManifest verifyInstalled(String id, String version) {
        return verifiedManifest(id, version);
    }

    /** The active version of a package, re-verified, with its payload and dependencies, ready to be loaded or run. */
    public synchronized Loadable loadActive(String id) {
        requireIntact();
        String version = state.getProperty(activeKey(id));
        if (version == null) {
            throw new PackageException(Reason.NOT_FOUND, id + " has no active version");
        }
        String grants = state.getProperty(approvalKey(id, version));
        if (grants == null) {
            throw new PackageException(Reason.NOT_FOUND, id + " " + version + " is active but not approved");
        }
        Path dir = versionDir(id, version);
        byte[] manifestBytes = read(dir.resolve("package.manifest"));
        byte[] payload = read(dir.resolve("payload.jar"));
        PackageVerifier.Verified v = PackageVerifier.verify(manifestBytes, payload, publishers);
        List<byte[]> deps = new ArrayList<>();
        for (PackageManifest.Dependency d : v.manifest().depends()) {
            deps.add(dependencyPayload(v.manifest(), d));
        }
        return new Loadable(v.manifest(), v.publisher(), payload, deps, parseGrants(grants));
    }

    /** The stored package (manifest + payload) whose payload has this SHA-256, if installed. Not verified: the receiver does. */
    public synchronized Optional<PackageArchive> archiveByPayload(String sha256) {
        for (Installed i : list()) {
            if (i.manifest().payloadSha256().equals(sha256)) {
                Path dir = versionDir(i.id(), i.version().toString());
                return Optional.of(new PackageArchive(read(dir.resolve("package.manifest")), read(dir.resolve("payload.jar"))));
            }
        }
        return Optional.empty();
    }

    /** Ids whose active version runs in the node's JVM. */
    public synchronized List<String> activeJvmPackages() {
        return list().stream().filter(i -> i.active() && i.manifest().sandbox() == PackageManifest.Sandboxing.JVM)
                .map(Installed::id).toList();
    }

    // ── running process packages ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the active version of a process package in the process sandbox, with a filesystem view limited to its approved
     * grants. The verified payload is copied to a private temporary file first, so what runs is what was checked.
     */
    public SandboxResult run(String id, List<String> args, FileSystemService fs, CapabilityRegistry registry,
                             SandboxProfile profile, Sandbox sandbox) {
        Loadable l = loadActive(id);
        if (l.manifest().sandbox() != PackageManifest.Sandboxing.PROCESS) {
            throw new PackageException(Reason.CONFLICT, l.manifest() + " is a JVM module; it is loaded at boot, not run");
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("noray-pkg-");
            StoreFiles.restrict(work);
            List<Path> classpath = new ArrayList<>();
            classpath.add(writePrivate(work.resolve("payload.jar"), l.payload()));
            for (int i = 0; i < l.dependencyPayloads().size(); i++) {
                classpath.add(writePrivate(work.resolve("dep" + i + ".jar"), l.dependencyPayloads().get(i)));
            }
            CapabilitySet g = l.grants();
            String moduleId = "pkg:" + id;
            FileSystemService scoped = new ScopedFileSystemService(fs, g.scopes("fs.read"), g.scopes("fs.write"),
                    g.scopes("fs.create"), g.scopes("fs.delete"), moduleId, registry);
            audit.record("run", l.manifest().toString(), "args=" + String.join(" ", args));
            SandboxResult r = sandbox.run(new SandboxSpec(classpath, l.manifest().main(), args, profile), scoped);
            audit.record("run-exit", l.manifest().toString(), "exit=" + r.exitCode());
            return r;
        } catch (IOException e) {
            throw new PackageException(Reason.IO, "cannot prepare " + id + " to run", e);
        } finally {
            deleteQuietly(work);
        }
    }

    private static Path writePrivate(Path p, byte[] data) throws IOException {
        Files.write(p, data);
        StoreFiles.restrict(p);
        return p;
    }

    static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temporary files; the OS cleans /tmp
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private PackageManifest verifiedManifest(String id, String version) {
        Path dir = versionDir(id, version);
        if (!Files.exists(dir.resolve("package.manifest"))) {
            throw new PackageException(Reason.NOT_FOUND, id + " " + version + " is not installed");
        }
        return PackageVerifier.verify(read(dir.resolve("package.manifest")), read(dir.resolve("payload.jar")), publishers)
                .manifest();
    }

    private PackageManifest manifestOf(String id, String version) {
        return PackageManifest.parse(read(versionDir(id, version).resolve("package.manifest")));
    }

    private void checkDependencies(PackageManifest m) {
        for (PackageManifest.Dependency d : m.depends()) {
            dependencyPayload(m, d);
        }
    }

    /** The payload of the installed, approved version of {@code d} whose hash is exactly the one required. */
    private byte[] dependencyPayload(PackageManifest m, PackageManifest.Dependency d) {
        for (Version v : versions(d.id())) {
            PackageManifest dm;
            try {
                dm = manifestOf(d.id(), v.toString());
            } catch (PackageException e) {
                continue;
            }
            if (dm.payloadSha256().equals(d.payloadSha256())) {
                if (state.getProperty(approvalKey(d.id(), v.toString())) == null) {
                    throw new PackageException(Reason.NOT_FOUND, m + " needs " + dm + ", which is installed but not approved");
                }
                Path dir = versionDir(d.id(), v.toString());
                byte[] payload = read(dir.resolve("payload.jar"));
                PackageVerifier.verify(read(dir.resolve("package.manifest")), payload, publishers);
                return payload;
            }
        }
        throw new PackageException(Reason.NOT_FOUND, m + " needs " + d + ", which is not installed");
    }

    private List<String> ids() {
        try (Stream<Path> s = Files.list(root.resolve("store"))) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new PackageException(Reason.IO, "cannot list package store", e);
        }
    }

    private List<Version> versions(String id) {
        Path dir = root.resolve("store").resolve(id);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            List<Version> out = new ArrayList<>();
            for (Path p : s.toList()) {
                if (!Files.exists(p.resolve("package.manifest"))) continue; // interrupted install
                try {
                    out.add(Version.parse(p.getFileName().toString()));
                } catch (PackageException ignored) {
                    // not ours
                }
            }
            return out;
        } catch (IOException e) {
            throw new PackageException(Reason.IO, "cannot list " + dir, e);
        }
    }

    private Path versionDir(String id, String version) {
        PackageManifest.malformedUnlessId(id);
        Version.parse(version);
        return root.resolve("store").resolve(id).resolve(version);
    }

    private List<String> history(String id) {
        return new ArrayList<>(splitGrants(state.getProperty(historyKey(id))));
    }

    private static CapabilitySet parseGrants(String grants) {
        try {
            return CapabilitySet.parse(grants);
        } catch (IllegalArgumentException e) {
            throw new PackageException(Reason.MALFORMED, "grants: " + e.getMessage());
        }
    }

    private static List<String> splitGrants(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
    }

    private static String approvalKey(String id, String version) { return "approved." + id + "@" + version; }
    private static String activeKey(String id) { return "active." + id; }
    private static String historyKey(String id) { return "history." + id; }

    private void save() {
        StringBuilder sb = new StringBuilder("# package approvals and active versions (managed by pkg; do not edit)\n");
        for (String k : new TreeSet<>(state.stringPropertyNames())) {
            sb.append(k).append('=').append(state.getProperty(k).replace("\\", "\\\\")).append('\n');
        }
        try {
            StoreFiles.writeAtomically(root.resolve("state.properties"), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PackageException(Reason.IO, "cannot save package state", e);
        }
        seal();
    }

    private static byte[] read(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new PackageException(Reason.IO, "cannot read " + p, e);
        }
    }

    private static String safeId(PackageArchive a) {
        try {
            return PackageManifest.parse(a.manifest()).toString();
        } catch (PackageException e) {
            return "(unparseable manifest)";
        }
    }
}
