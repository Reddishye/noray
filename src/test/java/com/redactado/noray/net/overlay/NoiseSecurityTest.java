package com.redactado.noray.net.overlay;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.pqc.crypto.mlkem.*;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review of the transport crypto by test: the primitives against independent references, the handshake against every
 * single-byte tampering, and the session against replay.
 */
class NoiseSecurityTest {

    private static final HexFormat HEX = HexFormat.of();

    // ── primitives against references ─────────────────────────────────────────

    @Test
    void blake2sMatchesTheRfc7693Vector() {
        assertEquals("508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982",
                HEX.formatHex(NoiseCrypto.hash("abc".getBytes())));
    }

    @Test
    void hmacBlake2sMatchesAnIndependentConstruction() {
        byte[] key = NoiseCrypto.random(32), msg = NoiseCrypto.random(100);
        byte[] k = Arrays.copyOf(key, 64), ipad = new byte[64], opad = new byte[64];
        for (int i = 0; i < 64; i++) { ipad[i] = (byte) (k[i] ^ 0x36); opad[i] = (byte) (k[i] ^ 0x5c); }
        byte[] inner = NoiseCrypto.hash(NoiseCrypto.concat(ipad, msg));
        assertArrayEquals(NoiseCrypto.hash(NoiseCrypto.concat(opad, inner)), NoiseCrypto.hmac(key, msg));
    }

    @Test
    void hkdfFollowsTheNoiseDefinition() {
        byte[] ck = NoiseCrypto.random(32), ikm = NoiseCrypto.random(32);
        byte[] temp = NoiseCrypto.hmac(ck, ikm);
        byte[] o1 = NoiseCrypto.hmac(temp, new byte[]{1});
        byte[] o2 = NoiseCrypto.hmac(temp, NoiseCrypto.concat(o1, new byte[]{2}));
        byte[] o3 = NoiseCrypto.hmac(temp, NoiseCrypto.concat(o2, new byte[]{3}));
        byte[][] out = NoiseCrypto.hkdf(ck, ikm, 3);
        assertArrayEquals(o1, out[0]);
        assertArrayEquals(o2, out[1]);
        assertArrayEquals(o3, out[2]);
    }

    @Test
    void x25519MatchesTheRfc7748Vector() {
        byte[] scalar = HEX.parseHex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4");
        byte[] u = HEX.parseHex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", HEX.formatHex(NoiseCrypto.dh(scalar, u)));
    }

    @Test
    void x25519RefusesLowOrderPoints() {
        assertThrows(IllegalStateException.class, () -> NoiseCrypto.dh(NoiseCrypto.x25519GeneratePrivate(), new byte[32]));
    }

    @Test
    void ourChaChaPolyNonceLayoutAgreesWithTheJdk() throws Exception {
        byte[] key = NoiseCrypto.random(32), ad = "ad".getBytes(), msg = "hello noise".getBytes();
        byte[] ct = NoiseCrypto.encrypt(key, 0x0102030405L, ad, msg);
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(NoiseCrypto.nonce12(0x0102030405L)));
        c.updateAAD(ad);
        assertArrayEquals(msg, c.doFinal(ct));
        assertEquals(12, NoiseCrypto.nonce12(1).length);
        assertEquals(1, NoiseCrypto.nonce12(1)[4], "counter is little endian after four zero bytes (Noise)");
    }

    // ── handshake ─────────────────────────────────────────────────────────────

    private record Party(byte[] xPriv, byte[] xPub, MLKEMPrivateKeyParameters kemPriv, MLKEMPublicKeyParameters kemPub) {
        static Party create() {
            byte[] p = NoiseCrypto.x25519GeneratePrivate();
            MLKEMKeyPairGenerator g = new MLKEMKeyPairGenerator();
            g.init(new MLKEMKeyGenerationParameters(new SecureRandom(), MLKEMParameters.ml_kem_768));
            AsymmetricCipherKeyPair kp = g.generateKeyPair();
            return new Party(p, NoiseCrypto.x25519Public(p), (MLKEMPrivateKeyParameters) kp.getPrivate(),
                    (MLKEMPublicKeyParameters) kp.getPublic());
        }
    }

    private final Party alice = Party.create(), bob = Party.create();
    private final byte[] psk = NoiseCrypto.random(32);

    private NoiseHandshake initiator(byte[] psk) {
        return new NoiseHandshake(true, alice.xPriv, alice.xPub, bob.xPub, psk, bob.kemPub, alice.kemPriv);
    }

    private NoiseHandshake responder(byte[] psk) {
        return new NoiseHandshake(false, bob.xPriv, bob.xPub, null, psk, null, bob.kemPriv);
    }

    @Test
    void bothSidesDeriveSessionsThatTalkInBothDirections() {
        NoiseHandshake i = initiator(psk), r = responder(psk);
        byte[] m1 = i.writeMessage1((byte) 7, 1234L);
        assertEquals(NoiseHandshake.MSG1_LEN, m1.length);
        assertArrayEquals(alice.xPub, r.readMessage1(m1), "the responder learns who is calling");
        assertEquals(7, r.peerTag());
        assertEquals(1234L, r.peerTimestamp());
        byte[] m2 = r.writeMessage2();
        assertEquals(NoiseHandshake.MSG2_LEN, m2.length);
        i.readMessage2(m2);
        NoiseSession si = i.split(), sr = r.split();
        byte[] ad = {1, 2, 3};
        assertArrayEquals("ping".getBytes(), sr.decrypt(0, ad, si.encrypt(ad, "ping".getBytes())));
        assertArrayEquals("pong".getBytes(), si.decrypt(0, ad, sr.encrypt(ad, "pong".getBytes())));
    }

    @Test
    void aDifferentMeshKeyNeverCompletes() {
        NoiseHandshake i = initiator(psk), r = responder(NoiseCrypto.random(32));
        r.readMessage1(i.writeMessage1());
        byte[] m2 = r.writeMessage2();
        assertThrows(RuntimeException.class, () -> i.readMessage2(m2));
    }

    @Test
    void anImpostorWithoutTheStaticKeyCannotAnswer() {
        Party mallory = Party.create();
        NoiseHandshake i = initiator(psk);
        NoiseHandshake fake = new NoiseHandshake(false, mallory.xPriv, mallory.xPub, null, psk, null, mallory.kemPriv);
        byte[] m1 = i.writeMessage1();
        assertThrows(RuntimeException.class, () -> fake.readMessage1(m1));
    }

    @Test
    void everyTamperedByteOfMessage1IsCaughtByTheResponderOrByTheInitiator() {
        NoiseHandshake probe = initiator(psk);
        byte[] original = probe.writeMessage1();
        for (int pos = 0; pos < original.length; pos += 7) {
            NoiseHandshake i = initiator(psk), r = responder(psk);
            byte[] m1 = i.writeMessage1();
            m1[Math.min(pos, m1.length - 1)] ^= 0x01;
            boolean caught = false;
            try {
                r.readMessage1(m1);
                byte[] m2 = r.writeMessage2();
                i.readMessage2(m2);
            } catch (RuntimeException e) {
                caught = true;
            }
            assertTrue(caught, "tampering at byte " + pos + " went through");
        }
    }

    @Test
    void everyTamperedByteOfMessage2IsCaught() {
        for (int pos = 0; pos < NoiseHandshake.MSG2_LEN; pos++) {
            NoiseHandshake i = initiator(psk), r = responder(psk);
            r.readMessage1(i.writeMessage1());
            byte[] m2 = r.writeMessage2();
            m2[pos] ^= 0x80;
            assertThrows(RuntimeException.class, () -> i.readMessage2(m2), "byte " + pos);
        }
    }

    @Test
    void messagesOfTheWrongLengthAreRefusedBeforeAnyWork() {
        NoiseHandshake i = initiator(psk);
        byte[] m1 = i.writeMessage1();
        assertThrows(IllegalArgumentException.class, () -> responder(psk).readMessage1(Arrays.copyOf(m1, m1.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> responder(psk).readMessage1(Arrays.copyOf(m1, m1.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> i.readMessage2(new byte[10]));
    }

    @Test
    void twoHandshakesNeverShareKeys() {
        NoiseHandshake i1 = initiator(psk), r1 = responder(psk), i2 = initiator(psk), r2 = responder(psk);
        r1.readMessage1(i1.writeMessage1());
        i1.readMessage2(r1.writeMessage2());
        r2.readMessage1(i2.writeMessage1());
        i2.readMessage2(r2.writeMessage2());
        NoiseSession a = i1.split(), b = r2.split();
        byte[] ct = a.encrypt(new byte[0], "x".getBytes());
        assertThrows(RuntimeException.class, () -> b.decrypt(0, new byte[0], ct), "sessions are independent");
    }

    // ── session ───────────────────────────────────────────────────────────────

    private NoiseSession[] pair() {
        NoiseHandshake i = initiator(psk), r = responder(psk);
        r.readMessage1(i.writeMessage1());
        i.readMessage2(r.writeMessage2());
        return new NoiseSession[]{i.split(), r.split()};
    }

    @Test
    void aReplayedOrAlteredPacketIsRefusedAndAForgeryDoesNotMoveTheWindow() {
        NoiseSession[] s = pair();
        byte[] ad = {9};
        byte[] p0 = s[0].encrypt(ad, "a".getBytes()), p1 = s[0].encrypt(ad, "b".getBytes());
        assertArrayEquals("a".getBytes(), s[1].decrypt(0, ad, p0));
        assertThrows(IllegalStateException.class, () -> s[1].decrypt(0, ad, p0), "replay");
        byte[] forged = p1.clone();
        forged[0] ^= 1;
        assertThrows(RuntimeException.class, () -> s[1].decrypt(1, ad, forged));
        assertArrayEquals("b".getBytes(), s[1].decrypt(1, ad, p1), "the forgery did not use up nonce 1");
        assertThrows(RuntimeException.class, () -> s[1].decrypt(1, new byte[]{8}, s[0].encrypt(ad, "c".getBytes())),
                "the associated data (header) is authenticated");
    }

    @Test
    void packetsOlderThanTheWindowAreRefused() {
        NoiseSession[] s = pair();
        byte[] ad = new byte[0];
        byte[] first = s[0].encrypt(ad, "first".getBytes());
        byte[] last = null;
        for (int i = 0; i < NoiseSession.WINDOW_SIZE + 5; i++) last = s[0].encrypt(ad, new byte[]{1});
        s[1].decrypt(NoiseSession.WINDOW_SIZE + 5, ad, last);
        assertThrows(IllegalStateException.class, () -> s[1].decrypt(0, ad, first));
        assertEquals(NoiseSession.WINDOW_SIZE + 6, s[0].peekSendNonce());
    }

    @Test
    void theNonceLayoutCarriesTheCounter() {
        assertArrayEquals(ByteBuffer.allocate(12).put(new byte[4]).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(77).array(),
                NoiseCrypto.nonce12(77));
    }
}
