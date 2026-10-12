package com.redactado.noray.pkg;

import com.redactado.noray.identity.SigningKey;
import com.redactado.noray.pkg.PackageException.Reason;
import com.redactado.noray.pkg.PackageManifest.Sandboxing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The done criteria of the package system: a tampered manifest, a swapped payload, a downgrade, an unpinned publisher
 * and an approval wider than the request are each refused; plus the lifecycle around them.
 */
class PackageManagerTest {

    @TempDir
    Path tmp;

    private PackageManager pm;
    private SigningKey key;
    private final byte[] payload = "pretend this is a jar".getBytes(StandardCharsets.UTF_8);
    private static final List<String> REQ = List.of("fs.write:/mnt/pool/app", "fs.read:/mnt/pool/shared");

    @BeforeEach
    void setUp() {
        pm = new PackageManager(tmp.resolve("packages"));
        key = SigningKey.generate();
        pm.pinPublisher(TestPackages.publisher("example", false, 1, key));
    }

    private PackageArchive pkg(String version) {
        return TestPackages.archive(TestPackages.draft("com.example.app", version, Sandboxing.PROCESS, payload, "example", REQ),
                payload, key);
    }

    private static Reason refused(Runnable r) {
        return assertThrows(PackageException.class, r::run).reason();
    }

    @Test
    void installStoresWithoutApprovingOrActivating() {
        PackageManifest m = pm.install(pkg("1.0.0"), false);
        assertEquals("com.example.app 1.0.0", m.toString());
        var i = pm.list().getFirst();
        assertFalse(i.approved());
        assertFalse(i.active());
        assertEquals(Reason.NOT_FOUND, refused(() -> pm.loadActive("com.example.app")));
        pm.install(pkg("1.0.0"), false); // identical: idempotent
        assertEquals(1, pm.list().size());
    }

    @Test
    void aTamperedManifestIsRefused() {
        PackageArchive good = pkg("1.0.0");
        String text = new String(good.manifest(), StandardCharsets.UTF_8);
        // widen a request after signing
        byte[] widened = text.replace("fs.read:/mnt/pool/shared", "fs.write:/mnt/pool/xxxxx").getBytes(StandardCharsets.UTF_8);
        assertEquals(Reason.BAD_SIGNATURE, refused(() -> pm.install(new PackageArchive(widened, payload), false)));
        // slip in an optional line before the signatures
        byte[] added = text.replace("publisher=example\n", "publisher=example\ndescription=harmless\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(Reason.BAD_SIGNATURE, refused(() -> pm.install(new PackageArchive(added, payload), false)));
        // strip the signature
        byte[] unsigned = text.substring(0, text.indexOf("signature=")).getBytes(StandardCharsets.UTF_8);
        assertEquals(Reason.BAD_SIGNATURE, refused(() -> pm.install(new PackageArchive(unsigned, payload), false)));
        assertTrue(pm.list().isEmpty());
    }

    @Test
    void aSwappedPayloadIsRefused() {
        PackageArchive good = pkg("1.0.0");
        byte[] evil = payload.clone();
        evil[0] ^= 1;
        assertEquals(Reason.PAYLOAD_MISMATCH, refused(() -> pm.install(new PackageArchive(good.manifest(), evil), false)));
        assertEquals(Reason.PAYLOAD_MISMATCH, refused(() -> pm.install(new PackageArchive(good.manifest(), new byte[3]), false)));
    }

    @Test
    void aDowngradeIsRefusedUnlessAllowed() {
        pm.install(pkg("1.2.0"), false);
        assertEquals(Reason.DOWNGRADE, refused(() -> pm.install(pkg("1.1.9"), false)));
        assertEquals(Reason.DOWNGRADE, refused(() -> pm.install(pkg("1.2.0-rc.1"), false)));
        pm.install(pkg("1.1.9"), true);
        assertEquals(2, pm.list().size());
    }

    @Test
    void anUnpinnedPublisherOrAForeignKeyIsRefused() {
        SigningKey stranger = SigningKey.generate();
        PackageArchive byStranger = TestPackages.archive(
                TestPackages.draft("com.example.app", "1.0.0", Sandboxing.PROCESS, payload, "nobody", REQ), payload, stranger);
        assertEquals(Reason.UNPINNED_PUBLISHER, refused(() -> pm.install(byStranger, false)));
        PackageArchive impostor = TestPackages.archive(
                TestPackages.draft("com.example.app", "1.0.0", Sandboxing.PROCESS, payload, "example", REQ), payload, stranger);
        assertEquals(Reason.BAD_SIGNATURE, refused(() -> pm.install(impostor, false)), "right name, wrong key");
    }

    @Test
    void approvalCanNarrowButNeverWiden() {
        pm.install(pkg("1.0.0"), false);
        assertEquals(Reason.GRANT_EXCEEDS_REQUEST, refused(() -> pm.approve("com.example.app", "1.0.0", "fs.write:/mnt/pool", true)));
        assertEquals(Reason.GRANT_EXCEEDS_REQUEST, refused(() -> pm.approve("com.example.app", "1.0.0", "fs.write:/mnt/pool/shared", true)));
        assertEquals(Reason.GRANT_EXCEEDS_REQUEST, refused(() -> pm.approve("com.example.app", "1.0.0", "service:Keyring", true)));
        assertFalse(pm.list().getFirst().approved());
        // narrower than requested: read-only below the write scope
        assertEquals(List.of("fs.read:/mnt/pool/app/public"), pm.approve("com.example.app", "1.0.0", "fs.read:/mnt/pool/app/public", true));
        var l = pm.loadActive("com.example.app");
        assertTrue(l.grants().allows("fs.read", "/mnt/pool/app/public/x"));
        assertFalse(l.grants().allows("fs.write", "/mnt/pool/app/public/x"));
        // empty approval: runs with nothing
        assertEquals(List.of(), pm.approve("com.example.app", "1.0.0", "", true));
        // default: exactly what was requested
        assertEquals(REQ, pm.approve("com.example.app", "1.0.0", null, true));
    }

    @Test
    void requestsThePackageCouldNeverHaveAreRefusedAtInstall() {
        byte[] p = payload;
        PackageArchive processWantsService = TestPackages.archive(TestPackages.draft("com.example.app", "1.0.0",
                Sandboxing.PROCESS, p, "example", List.of("service:OnionService")), p, key);
        assertEquals(Reason.FORBIDDEN_REQUEST, refused(() -> pm.install(processWantsService, false)));
        PackageArchive jvmFromUntrusted = TestPackages.archive(TestPackages.draft("com.example.app", "1.0.0",
                Sandboxing.JVM, p, "example", List.of()), p, key);
        assertEquals(Reason.FORBIDDEN_REQUEST, refused(() -> pm.install(jvmFromUntrusted, false)));
        var future = new PackageManifest.Draft("com.example.app", "1.0.0", PackageManifest.NODE_API + 1, Sandboxing.PROCESS,
                "com.example.Main", TestPackages.Hex.sha256(p), p.length, "example", List.of(), List.of(), "");
        assertEquals(Reason.INCOMPATIBLE_API, refused(() -> pm.install(TestPackages.archive(future, p, key), false)));
    }

    @Test
    void thresholdPublishersNeedEnoughDistinctSignatures() {
        SigningKey a = SigningKey.generate(), b = SigningKey.generate(), c = SigningKey.generate();
        pm.pinPublisher(TestPackages.publisher("council", false, 2, a, b, c));
        var d = TestPackages.draft("com.example.voted", "1.0.0", Sandboxing.PROCESS, payload, "council", List.of());
        assertEquals(Reason.BAD_SIGNATURE, refused(() -> pm.install(TestPackages.archive(d, payload, a), false)));
        assertEquals(Reason.BAD_SIGNATURE, refused(() -> pm.install(TestPackages.archive(d, payload, a, key), false)),
                "a key of another publisher does not count");
        pm.install(TestPackages.archive(d, payload, a, c), false);
    }

    @Test
    void versionsAreImmutable() {
        pm.install(pkg("1.0.0"), false);
        byte[] other = "another jar".getBytes(StandardCharsets.UTF_8);
        PackageArchive sameVersion = TestPackages.archive(
                TestPackages.draft("com.example.app", "1.0.0", Sandboxing.PROCESS, other, "example", REQ), other, key);
        assertEquals(Reason.CONFLICT, refused(() -> pm.install(sameVersion, false)));
    }

    @Test
    void activationRollbackAndRemoval() {
        pm.install(pkg("1.0.0"), false);
        pm.install(pkg("1.1.0"), false);
        assertEquals(Reason.NOT_FOUND, refused(() -> pm.activate("com.example.app", "1.1.0")), "not approved");
        pm.approve("com.example.app", "1.0.0", null, true);
        pm.approve("com.example.app", "1.1.0", null, true);
        assertEquals("1.1.0", pm.activeVersion("com.example.app").orElseThrow());
        assertEquals("1.0.0", pm.rollback("com.example.app"));
        assertEquals("1.0.0", pm.activeVersion("com.example.app").orElseThrow());
        assertEquals(Reason.CONFLICT, refused(() -> pm.remove("com.example.app", "1.0.0")), "active");
        pm.remove("com.example.app", "1.1.0");
        assertEquals(Reason.NOT_FOUND, refused(() -> pm.rollback("com.example.app")), "1.1.0 is gone");
        pm.deactivate("com.example.app");
        pm.remove("com.example.app", "1.0.0");
        assertTrue(pm.list().isEmpty());
        assertFalse(Files.exists(tmp.resolve("packages/store/com.example.app")));
    }

    @Test
    void stateSurvivesARestart() {
        pm.install(pkg("1.0.0"), false);
        pm.approve("com.example.app", "1.0.0", "fs.read:/mnt/pool/shared", true);
        PackageManager again = new PackageManager(tmp.resolve("packages"));
        assertEquals("1.0.0", again.activeVersion("com.example.app").orElseThrow());
        assertEquals(List.of("fs.read:/mnt/pool/shared"), again.list().getFirst().grants());
        assertTrue(again.publishers().get("example").isPresent());
    }

    @Test
    void everyLoadReverifiesAgainstTheCurrentTrustAndTheStoredBytes() throws Exception {
        pm.install(pkg("1.0.0"), false);
        pm.approve("com.example.app", "1.0.0", null, true);
        pm.loadActive("com.example.app");
        // a swapped file in the store is caught
        Path stored = tmp.resolve("packages/store/com.example.app/1.0.0/payload.jar");
        byte[] original = Files.readAllBytes(stored);
        Files.write(stored, "evil".getBytes(StandardCharsets.UTF_8));
        assertEquals(Reason.PAYLOAD_MISMATCH, refused(() -> pm.loadActive("com.example.app")));
        Files.write(stored, original);
        // unpinning the publisher stops it
        pm.unpinPublisher("example");
        assertEquals(Reason.UNPINNED_PUBLISHER, refused(() -> pm.loadActive("com.example.app")));
    }

    @Test
    void dependenciesMustBeInstalledByExactHashAndApproved() {
        byte[] lib = "library".getBytes(StandardCharsets.UTF_8);
        String libHash = TestPackages.Hex.sha256(lib);
        var app = new PackageManifest.Draft("com.example.app", "1.0.0", PackageManifest.NODE_API, Sandboxing.PROCESS,
                "com.example.Main", TestPackages.Hex.sha256(payload), payload.length, "example", List.of(),
                List.of("com.example.lib@" + libHash), "");
        pm.install(TestPackages.archive(app, payload, key), false);
        assertEquals(Reason.NOT_FOUND, refused(() -> pm.approve("com.example.app", "1.0.0", null, true)), "missing");
        pm.install(TestPackages.archive(TestPackages.draft("com.example.lib", "2.0.0", Sandboxing.PROCESS, lib, "example",
                List.of()), lib, key), false);
        assertEquals(Reason.NOT_FOUND, refused(() -> pm.approve("com.example.app", "1.0.0", null, true)), "not approved");
        pm.approve("com.example.lib", "2.0.0", null, false);
        pm.approve("com.example.app", "1.0.0", null, true);
        assertArrayEquals(lib, pm.loadActive("com.example.app").dependencyPayloads().getFirst());
    }

    @Test
    void identifiersFromTheShellCannotEscapeTheStore() {
        assertEquals(Reason.MALFORMED, refused(() -> pm.approve("../../etc", "1.0.0", null, true)));
        assertEquals(Reason.MALFORMED, refused(() -> pm.remove("com.example.app", "../../../x")));
    }

    @Test
    void theAuditTrailIsHashChained() throws Exception {
        pm.install(pkg("1.0.0"), false);
        pm.approve("com.example.app", "1.0.0", null, true);
        assertThrows(PackageException.class, () -> pm.install(new PackageArchive(pkg("2.0.0").manifest(), new byte[1]), false));
        assertEquals(-1, pm.audit().verify());
        List<String> tail = pm.audit().tail(10);
        assertTrue(tail.stream().anyMatch(l -> l.contains("\"event\":\"install-refused\"")));
        assertTrue(tail.stream().anyMatch(l -> l.contains("\"event\":\"approve\"")));
        Path log = tmp.resolve("packages/audit.log");
        List<String> lines = Files.readAllLines(log);
        lines.set(1, lines.get(1).replace("install", "instal1"));
        Files.write(log, lines);
        assertEquals(3, new PackageManager(tmp.resolve("packages")).audit().verify(), "the line after the edit no longer chains");
    }

    @Test
    void sealedApprovalsCannotBeEditedOffline() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Path root = tmp.resolve("sealed");
        PackageManager sealed = new PackageManager(root, key);
        sealed.pinPublisher(TestPackages.publisher("example", false, 1, this.key));
        sealed.install(pkg("1.0.0"), false);
        sealed.approve("com.example.app", "1.0.0", "fs.read:/mnt/pool/shared", true);
        assertNull(new PackageManager(root, key).integrityProblem(), "intact across a restart");

        // someone with the disk widens the approval by hand
        Path state = root.resolve("state.properties");
        Files.writeString(state, Files.readString(state).replace("fs.read:/mnt/pool/shared", "fs.write:/mnt/pool/app"));
        PackageManager after = new PackageManager(root, key);
        assertNotNull(after.integrityProblem());
        assertEquals(Reason.CONFLICT, refused(() -> after.loadActive("com.example.app")), "nothing loads");
        assertEquals(Reason.CONFLICT, refused(() -> after.approve("com.example.app", "1.0.0", null, true)));
        assertTrue(after.audit().tail(3).stream().anyMatch(l -> l.contains("integrity-failed")));
        // an operator who reviewed it accepts it explicitly
        after.reseal();
        assertNull(new PackageManager(root, key).integrityProblem());
        new PackageManager(root, key).loadActive("com.example.app");

        // a different key (another keyring) is the same as tampering
        assertNotNull(new PackageManager(root, new byte[32]).integrityProblem());
        // and deleting the seal does not help
        Files.delete(root.resolve("state.properties.mac"));
        assertNotNull(new PackageManager(root, key).integrityProblem());
    }
}
