package com.redactado.noray.identity;

import com.redactado.noray.data.crypto.Keyring;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IdentityTest {

    private static final Keyring.Params FAST = new Keyring.Params(64, 1, 1);

    private final TestClock clock = new TestClock();

    private static NodeId id(int n) {
        return new NodeId(String.format("%064x", n));
    }

    private Certificate cert(NodeId node, SigningKey nodeKey, Set<Op> ops, Duration validFor, long serial) {
        Instant now = clock.instant();
        return Certificate.unsigned(node, nodeKey.publicKey(), new byte[0], ops, now.minusSeconds(60), now.plus(validFor), serial);
    }

    private static void assertDenied(Runnable r) {
        FsException e = assertThrows(FsException.class, r::run);
        assertEquals(FsErrorCode.DENIED, e.code(), e.getMessage());
    }

    // ── signatures ─────────────────────────────────────────────────────────────

    @Test
    void signaturesAreBoundToTheirPurpose() {
        SigningKey k = SigningKey.generate();
        byte[] msg = "hello".getBytes();
        byte[] sig = k.sign("purpose-a", msg);
        assertTrue(SigningKey.verify(k.publicKey(), "purpose-a", msg, sig));
        assertFalse(SigningKey.verify(k.publicKey(), "purpose-b", msg, sig), "another purpose never verifies");
        assertFalse(SigningKey.verify(k.publicKey(), "purpose-a", "hellp".getBytes(), sig));
        assertFalse(SigningKey.verify(SigningKey.generate().publicKey(), "purpose-a", msg, sig));
        assertFalse(SigningKey.verify(new byte[5], "purpose-a", msg, sig), "garbage keys do not throw");
    }

    // ── certificates ───────────────────────────────────────────────────────────

    @Test
    void onlyTrustedAuthoritiesMakeACertificateValid() {
        SigningKey authority = SigningKey.generate();
        SigningKey stranger = SigningKey.generate();
        SigningKey node = SigningKey.generate();
        TrustRoot trust = new TrustRoot(List.of(authority.publicKey()), 1);
        Certificate c = cert(id(1), node, Role.PEER.ops(), Duration.ofDays(1), 1);

        trust.verify(c.endorse(authority), clock.instant());
        assertDenied(() -> trust.verify(c, clock.instant())); // not endorsed
        assertDenied(() -> trust.verify(c.endorse(stranger), clock.instant())); // endorsed by someone untrusted
        assertDenied(() -> trust.verify(c.endorse(node), clock.instant())); // self-signed
    }

    @Test
    void aCertificateCannotBeEditedAfterSigning() {
        SigningKey authority = SigningKey.generate();
        TrustRoot trust = new TrustRoot(List.of(authority.publicKey()), 1);
        Certificate peer = cert(id(2), SigningKey.generate(), Role.PEER.ops(), Duration.ofDays(1), 1).endorse(authority);
        byte[] raw = peer.encode();
        int opsOffset = 4 + 1 + 32 + 32 + 1 + 2 + 3; // low byte of the operation mask
        raw[opsOffset] |= (byte) (1 << Op.DELETE.ordinal());
        Certificate forged = Certificate.decode(raw);
        assertTrue(forged.allows(Op.DELETE));
        assertDenied(() -> trust.verify(forged, clock.instant()));
    }

    @Test
    void thresholdNeedsDistinctAuthorities() {
        SigningKey a = SigningKey.generate(), b = SigningKey.generate(), c = SigningKey.generate();
        TrustRoot trust = new TrustRoot(List.of(a.publicKey(), b.publicKey(), c.publicKey()), 2);
        Certificate base = cert(id(3), SigningKey.generate(), Role.PEER.ops(), Duration.ofDays(1), 1);

        assertDenied(() -> trust.verify(base.endorse(a), clock.instant()));
        assertDenied(() -> trust.verify(base.endorse(a).endorse(a), clock.instant()));
        trust.verify(base.endorse(a).endorse(c), clock.instant());
        // endorsements gathered separately (offline) can be merged
        trust.verify(base.endorse(b).merge(base.endorse(c)), clock.instant());
        assertThrows(IllegalArgumentException.class, () -> base.endorse(a).merge(
                cert(id(4), SigningKey.generate(), Role.PEER.ops(), Duration.ofDays(1), 1).endorse(a)));
    }

    @Test
    void invalidTrustRootsAreRefused() {
        byte[] k = SigningKey.generate().publicKey();
        assertThrows(IllegalArgumentException.class, () -> new TrustRoot(List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new TrustRoot(List.of(k), 2));
        assertThrows(IllegalArgumentException.class, () -> new TrustRoot(List.of(k), 0));
        assertThrows(IllegalArgumentException.class, () -> new TrustRoot(List.of(k, k.clone()), 2), "duplicates count once");
    }

    @Test
    void validityWindowIsEnforcedWithABoundedSkew() {
        SigningKey authority = SigningKey.generate();
        TrustRoot trust = new TrustRoot(List.of(authority.publicKey()), 1, Duration.ofMinutes(5), null);
        Instant now = clock.instant();
        Certificate c = Certificate.unsigned(id(5), SigningKey.generate().publicKey(), null, Role.PEER.ops(),
                now.plusSeconds(600), now.plusSeconds(3600), 1).endorse(authority);

        assertDenied(() -> trust.verify(c, now)); // not valid yet, beyond the skew
        trust.verify(c, now.plusSeconds(400)); // within skew of the start
        trust.verify(c, now.plusSeconds(3600 + 200)); // within skew of the end
        assertDenied(() -> trust.verify(c, now.plusSeconds(3600 + 400)));
    }

    @Test
    void textFormsRoundTrip() {
        SigningKey authority = SigningKey.generate();
        Certificate c = cert(id(6), SigningKey.generate(), Role.OPERATOR.ops(), Duration.ofDays(1), 7).endorse(authority);
        Certificate back = Certificate.parse(c.toText());
        assertArrayEquals(c.encode(), back.encode());
        assertEquals(Role.OPERATOR.ops(), back.ops());

        RevocationList list = RevocationList.unsigned(1, clock.millis(), Map.of(id(6), 100L)).endorse(authority);
        assertArrayEquals(list.encode(), RevocationList.parse(list.toText()).encode());

        EnrollmentRequest r = new EnrollmentRequest(id(6), SigningKey.generate().publicKey(), new byte[32]);
        assertEquals(r.fingerprint(), EnrollmentRequest.parse(r.toText()).fingerprint());

        assertThrows(IllegalArgumentException.class, () -> Certificate.parse("noray-cert:1:00"));
        assertThrows(IllegalArgumentException.class, () -> Certificate.decode(new byte[10]));
        assertThrows(IllegalArgumentException.class, () -> Certificate.parse("garbage"));
    }

    // ── revocation ─────────────────────────────────────────────────────────────

    @Test
    void revocationVoidsOldCertificatesButNotNewerOnes(@TempDir Path dir) {
        SigningKey authority = SigningKey.generate();
        TrustRoot trust = new TrustRoot(List.of(authority.publicKey()), 1, TrustRoot.DEFAULT_SKEW, dir.resolve("rev"));
        SigningKey nodeKey = SigningKey.generate();
        Certificate old = cert(id(7), nodeKey, Role.PEER.ops(), Duration.ofDays(30), 10).endorse(authority);
        trust.verify(old, clock.instant());

        RevocationList v1 = RevocationList.unsigned(1, clock.millis(), Map.of(id(7), 11L)).endorse(authority);
        assertTrue(trust.acceptRevocations(v1));
        assertDenied(() -> trust.verify(old, clock.instant()));
        trust.verify(cert(id(7), nodeKey, Role.PEER.ops(), Duration.ofDays(30), 11).endorse(authority), clock.instant());
        trust.verify(cert(id(8), nodeKey, Role.PEER.ops(), Duration.ofDays(30), 1).endorse(authority), clock.instant());

        assertFalse(trust.acceptRevocations(v1), "same version again is a no-op");
        assertFalse(trust.acceptRevocations(RevocationList.empty()));
    }

    @Test
    void revocationListsAreCumulativeAndMustBeEndorsed() {
        SigningKey a = SigningKey.generate(), b = SigningKey.generate();
        TrustRoot trust = new TrustRoot(List.of(a.publicKey(), b.publicKey()), 2);

        RevocationList v1 = RevocationList.unsigned(1, 0, Map.of(id(1), 5L));
        assertDenied(() -> trust.acceptRevocations(v1.endorse(a))); // one of two
        assertTrue(trust.acceptRevocations(v1.endorse(a).endorse(b)));

        RevocationList dropsOne = RevocationList.unsigned(2, 0, Map.of(id(2), 5L)).endorse(a).endorse(b);
        assertDenied(() -> trust.acceptRevocations(dropsOne)); // would un-revoke node 1
        RevocationList lowers = RevocationList.unsigned(2, 0, Map.of(id(1), 3L)).endorse(a).endorse(b);
        assertDenied(() -> trust.acceptRevocations(lowers));
        RevocationList adds = RevocationList.unsigned(2, 0, Map.of(id(1), 5L, id(2), 9L)).endorse(a).endorse(b);
        assertTrue(trust.acceptRevocations(adds));
        assertEquals(2, trust.revocations().version());
    }

    @Test
    void revocationsSurviveARestartAndATamperedFileStopsTheBoot(@TempDir Path dir) throws Exception {
        SigningKey authority = SigningKey.generate();
        Path file = dir.resolve("rev");
        TrustRoot first = new TrustRoot(List.of(authority.publicKey()), 1, TrustRoot.DEFAULT_SKEW, file);
        first.acceptRevocations(RevocationList.unsigned(4, 0, Map.of(id(9), 50L)).endorse(authority));

        TrustRoot second = new TrustRoot(List.of(authority.publicKey()), 1, TrustRoot.DEFAULT_SKEW, file);
        assertEquals(4, second.revocations().version());
        assertDenied(() -> second.verify(cert(id(9), SigningKey.generate(), Role.PEER.ops(), Duration.ofDays(1), 3)
                .endorse(authority), clock.instant()));

        byte[] raw = Files.readAllBytes(file);
        raw[20] ^= 1; // flip a bit of the content
        Files.write(file, raw);
        assertThrows(RuntimeException.class,
                () -> new TrustRoot(List.of(authority.publicKey()), 1, TrustRoot.DEFAULT_SKEW, file));
    }

    // ── peer directory ─────────────────────────────────────────────────────────

    @Test
    void directoryAcceptsOnlyACertificateForTheNodeThatPresentsIt() {
        SigningKey authority = SigningKey.generate();
        TrustRoot trust = new TrustRoot(List.of(authority.publicKey()), 1);
        PeerDirectory dir = new PeerDirectory(trust, clock);
        Certificate forB = cert(id(11), SigningKey.generate(), Role.OPERATOR.ops(), Duration.ofDays(1), 1).endorse(authority);

        assertEquals(PeerDirectory.Offer.REJECTED, dir.offer(id(12), forB), "A cannot replay B's certificate");
        assertDenied(() -> dir.check(id(12), Op.GET));
        assertEquals(PeerDirectory.Offer.ACCEPTED, dir.offer(id(11), forB));
        dir.check(id(11), Op.DELETE);
        assertEquals(PeerDirectory.Offer.UNCHANGED, dir.offer(id(11), forB));
    }

    @Test
    void directoryEnforcesOperationsExpiryAndRevocationOnEveryRequest() {
        SigningKey authority = SigningKey.generate();
        TrustRoot trust = new TrustRoot(List.of(authority.publicKey()), 1, Duration.ofMinutes(1), null);
        PeerDirectory dir = new PeerDirectory(trust, clock);
        Certificate c = cert(id(13), SigningKey.generate(), Role.PEER.ops(), Duration.ofHours(1), 5).endorse(authority);
        assertEquals(PeerDirectory.Offer.ACCEPTED, dir.offer(id(13), c));

        dir.check(id(13), Op.PUT);
        dir.check(id(13), Op.GET);
        assertDenied(() -> dir.check(id(13), Op.DELETE));
        assertDenied(() -> dir.check(id(13), Op.LIST));

        RevocationList rev = RevocationList.unsigned(1, clock.millis(), Map.of(id(13), 6L)).endorse(authority);
        trust.acceptRevocations(rev);
        assertDenied(() -> dir.check(id(13), Op.GET));
        assertEquals(1, dir.purge().size());

        PeerDirectory other = new PeerDirectory(new TrustRoot(List.of(authority.publicKey()), 1, Duration.ofMinutes(1), null), clock);
        Certificate shortLived = cert(id(14), SigningKey.generate(), Role.PEER.ops(), Duration.ofMinutes(10), 1).endorse(authority);
        other.offer(id(14), shortLived);
        other.check(id(14), Op.GET);
        clock.advance(Duration.ofMinutes(20));
        assertDenied(() -> other.check(id(14), Op.GET));
    }

    @Test
    void anOlderCertificateNeverReplacesANewerOne() {
        SigningKey authority = SigningKey.generate();
        PeerDirectory dir = new PeerDirectory(new TrustRoot(List.of(authority.publicKey()), 1), clock);
        SigningKey nk = SigningKey.generate();
        Certificate operator = cert(id(15), nk, Role.OPERATOR.ops(), Duration.ofDays(1), 2).endorse(authority);
        Certificate peerOnly = cert(id(15), nk, Role.PEER.ops(), Duration.ofDays(1), 1).endorse(authority);
        dir.offer(id(15), peerOnly);
        dir.offer(id(15), operator);
        assertEquals(PeerDirectory.Offer.UNCHANGED, dir.offer(id(15), peerOnly));
        dir.check(id(15), Op.DELETE);
    }

    // ── node identity at rest ──────────────────────────────────────────────────

    @Test
    void nodeKeyIsSealedWithTheKeyringAndSurvivesRestart(@TempDir Path dir) throws Exception {
        byte[] pub;
        try (Keyring k = Keyring.openOrCreate(dir.resolve("keyring"), "pw".toCharArray(), FAST)) {
            NodeIdentity first = NodeIdentity.loadOrCreate(dir, k);
            assertTrue(first.sealed());
            pub = first.publicKey();
            byte[] raw = Files.readAllBytes(dir.resolve("identity").resolve("node.key"));
            assertFalse(contains(raw, first.signingKey().seed()), "the seed is not on disk in clear");
        }
        try (Keyring k = Keyring.openOrCreate(dir.resolve("keyring"), "pw".toCharArray(), FAST)) {
            assertArrayEquals(pub, NodeIdentity.loadOrCreate(dir, k).publicKey());
        }
        assertThrows(IllegalStateException.class, () -> NodeIdentity.loadOrCreate(dir, null), "sealed key needs the keyring");
    }

    @Test
    void aPlainNodeKeyIsSealedOnceAKeyringAppears(@TempDir Path dir) throws Exception {
        NodeIdentity plain = NodeIdentity.loadOrCreate(dir, null);
        assertFalse(plain.sealed());
        Path file = dir.resolve("identity").resolve("node.key");
        assertTrue(contains(Files.readAllBytes(file), plain.signingKey().seed()));
        try (Keyring k = Keyring.openOrCreate(dir.resolve("keyring"), "pw".toCharArray(), FAST)) {
            NodeIdentity sealed = NodeIdentity.loadOrCreate(dir, k);
            assertTrue(sealed.sealed());
            assertArrayEquals(plain.publicKey(), sealed.publicKey(), "same identity, now protected");
            assertFalse(contains(Files.readAllBytes(file), plain.signingKey().seed()));
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    @Test
    void identityServiceInstallsOnlyItsOwnValidCertificate(@TempDir Path dir) throws Exception {
        SigningKey authority = SigningKey.generate();
        TrustRoot trust = new TrustRoot(List.of(authority.publicKey()), 1);
        NodeIdentity identity = NodeIdentity.loadOrCreate(dir, null);
        NodeId me = id(21);
        IdentityService svc = new IdentityService(me, new byte[32], identity, trust, new PeerDirectory(trust, clock),
                dir.resolve("identity").resolve("node.cert"), clock);

        EnrollmentRequest req = svc.enrollmentRequest();
        Certificate good = req.toCertificate(Role.PEER.ops(), clock.instant().minusSeconds(10),
                clock.instant().plus(Duration.ofDays(30)), 1).endorse(authority);

        Certificate otherKey = cert(me, SigningKey.generate(), Role.PEER.ops(), Duration.ofDays(1), 1).endorse(authority);
        assertDenied(() -> svc.install(otherKey));
        assertDenied(() -> svc.install(req.toCertificate(Role.PEER.ops(), clock.instant().minusSeconds(10),
                clock.instant().plus(Duration.ofDays(30)), 1))); // not endorsed
        svc.install(good);
        assertEquals(good.serial(), svc.certificate().orElseThrow().serial());

        IdentityService reopened = new IdentityService(me, new byte[32], identity, trust, new PeerDirectory(trust, clock),
                dir.resolve("identity").resolve("node.cert"), clock);
        assertTrue(reopened.certificate().isPresent(), "the certificate is remembered");
        assertEquals(HexFormat.of().formatHex(identity.publicKey()),
                HexFormat.of().formatHex(reopened.certificate().orElseThrow().signKey()));
    }
}
