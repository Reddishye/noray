package com.redactado.noray.net.overlay;

import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Noise_IKpsk2 + MixKey(ML-KEM-768) after message 1.
 */
public final class NoiseHandshake {

    private byte[] ck;
    private byte[] h;
    private byte[] k;
    private long n;
    private boolean hasK;

    private final byte[] sPriv;
    private final byte[] sPub;
    private byte[] ePriv;
    private byte[] ePub;
    private byte[] re;
    private byte[] rs;
    private byte[] remoteS;
    private byte[] psk;
    private int peerTag = -1;
    private long peerTimestamp = -1;

    /** Message 1 payload: psk tag (1) + sender's clock in milliseconds (8). */
    static final int PAYLOAD1 = 9;
    static final int KEM_CT = 1088; // ML-KEM-768 ciphertext
    static final int MSG1_LEN = 32 + (32 + NoiseCrypto.TAGLEN) + (PAYLOAD1 + NoiseCrypto.TAGLEN) + 2 + KEM_CT;
    static final int MSG2_LEN = 32 + NoiseCrypto.TAGLEN;
    private final boolean initiator;
    private final MLKEMPublicKeyParameters remoteKem;
    private final MLKEMPrivateKeyParameters localKemPriv;

    public NoiseHandshake(
            boolean initiator,
            byte[] sPriv,
            byte[] sPub,
            byte[] rs,
            byte[] psk,
            MLKEMPublicKeyParameters remoteKem,
            MLKEMPrivateKeyParameters localKemPriv
    ) {
        this.initiator = initiator;
        this.sPriv = sPriv;
        this.sPub = sPub;
        this.rs = rs;
        this.psk = psk == null ? new byte[32] : psk;
        this.remoteKem = remoteKem;
        this.localKemPriv = localKemPriv;
        byte[] pname = NoiseCrypto.protocolNameHash();
        this.ck = pname.clone();
        this.h = pname.clone();
        mixHash(NoiseCrypto.PROLOGUE);
        mixHash(initiator ? rs : sPub);
    }

    /** Responder: the pre-shared key to answer with, once message 1 told which generation the initiator holds. */
    public void usePsk(byte[] psk) {
        this.psk = psk;
    }

    /** Responder: the tag the initiator put in message 1 (encrypted) to say which mesh key it holds; -1 before. */
    public int peerTag() {
        return peerTag;
    }

    public byte[] remoteStatic() {
        return remoteS;
    }

    public byte[] writeMessage1() {
        return writeMessage1((byte) 0, System.currentTimeMillis());
    }

    /** Responder: the initiator's clock (milliseconds) as it said in message 1, -1 before. Used to refuse replays. */
    public long peerTimestamp() {
        return peerTimestamp;
    }

    /**
     * @param pskTag    one byte derived from the mesh key in use, so that a responder that knows two can pick
     * @param timestamp strictly increasing per initiator: a recorded message 1 is then useless to replay
     */
    public byte[] writeMessage1(byte pskTag, long timestamp) {
        ePriv = NoiseCrypto.x25519GeneratePrivate();
        ePub = NoiseCrypto.x25519Public(ePriv);
        mixHash(ePub);
        mixKey(NoiseCrypto.dh(ePriv, rs));
        byte[] encS = encryptAndHash(sPub);
        mixKey(NoiseCrypto.dh(sPriv, rs));
        byte[] payload = java.nio.ByteBuffer.allocate(PAYLOAD1).put(pskTag).putLong(timestamp).array();
        byte[] encPayload = encryptAndHash(payload);
        MLKEMGenerator gen = new MLKEMGenerator(new SecureRandom());
        org.bouncycastle.crypto.SecretWithEncapsulation swe = gen.generateEncapsulated(remoteKem);
        mixKey(swe.getSecret());
        byte[] ct = swe.getEncapsulation();
        mixHash(ct); // the post-quantum ciphertext is part of the transcript
        return NoiseCrypto.concat(ePub, encS, encPayload, u16(ct.length), ct);
    }

    public byte[] readMessage1(byte[] msg) {
        if (msg.length != MSG1_LEN) {
            throw new IllegalArgumentException("handshake message 1 has the wrong length");
        }
        re = Arrays.copyOfRange(msg, 0, 32);
        mixHash(re);
        mixKey(NoiseCrypto.dh(sPriv, re));
        int o = 32;
        byte[] encS = Arrays.copyOfRange(msg, o, o + 32 + NoiseCrypto.TAGLEN);
        o += encS.length;
        remoteS = decryptAndHash(encS);
        if (rs != null && rs.length == 32 && !constantTimeEquals(rs, remoteS)) {
            throw new IllegalStateException("static key mismatch");
        }
        mixKey(NoiseCrypto.dh(sPriv, remoteS));
        byte[] encPayload = Arrays.copyOfRange(msg, o, o + PAYLOAD1 + NoiseCrypto.TAGLEN);
        o += encPayload.length;
        byte[] payload = decryptAndHash(encPayload);
        peerTag = payload[0] & 0xff;
        peerTimestamp = java.nio.ByteBuffer.wrap(payload, 1, 8).getLong();
        int ctLen = ((msg[o] & 0xff) << 8) | (msg[o + 1] & 0xff);
        o += 2;
        if (ctLen != KEM_CT || o + ctLen != msg.length) {
            throw new IllegalArgumentException("handshake message 1 has a malformed ciphertext");
        }
        byte[] ct = Arrays.copyOfRange(msg, o, o + ctLen);
        byte[] ssPq = new MLKEMExtractor(localKemPriv).extractSecret(ct);
        mixKey(ssPq);
        mixHash(ct);
        return remoteS;
    }

    /** Responder: e, ee, se, psk (IKpsk2). */
    public byte[] writeMessage2() {
        ePriv = NoiseCrypto.x25519GeneratePrivate();
        ePub = NoiseCrypto.x25519Public(ePriv);
        mixHash(ePub);
        mixKey(NoiseCrypto.dh(ePriv, re));       // ee
        mixKey(NoiseCrypto.dh(ePriv, remoteS));  // se: our ephemeral with the initiator's static
        mixKeyAndHash(psk);
        byte[] encEmpty = encryptAndHash(new byte[0]);
        return NoiseCrypto.concat(ePub, encEmpty);
    }

    /** Initiator: e, ee, se, psk (IKpsk2). */
    public void readMessage2(byte[] msg) {
        if (msg.length != MSG2_LEN) {
            throw new IllegalArgumentException("handshake message 2 has the wrong length");
        }
        re = Arrays.copyOfRange(msg, 0, 32);
        mixHash(re);
        mixKey(NoiseCrypto.dh(ePriv, re));       // ee
        mixKey(NoiseCrypto.dh(sPriv, re));       // se: our static with the responder's ephemeral
        mixKeyAndHash(psk);
        decryptAndHash(Arrays.copyOfRange(msg, 32, msg.length));
    }

    public NoiseSession split() {
        byte[][] out = NoiseCrypto.hkdf(ck, new byte[0], 2);
        if (initiator) {
            return new NoiseSession(out[0], out[1], h);
        }
        return new NoiseSession(out[1], out[0], h);
    }

    private void mixHash(byte[] data) {
        h = NoiseCrypto.mixHash(h, data);
    }

    private void mixKey(byte[] ikm) {
        byte[][] o = NoiseCrypto.hkdf(ck, ikm, 2);
        ck = o[0];
        k = o[1];
        hasK = true;
        n = 0;
    }

    private void mixKeyAndHash(byte[] pskBytes) {
        byte[][] o = NoiseCrypto.hkdf(ck, pskBytes, 3);
        ck = o[0];
        mixHash(o[1]);
        k = o[2];
        hasK = true;
        n = 0;
    }

    private byte[] encryptAndHash(byte[] plaintext) {
        byte[] c = NoiseCrypto.encrypt(k, n++, h, plaintext);
        mixHash(c);
        return c;
    }

    private byte[] decryptAndHash(byte[] ciphertext) {
        byte[] p = NoiseCrypto.decrypt(k, n++, h, ciphertext);
        mixHash(ciphertext);
        return p;
    }

    private static byte[] u16(int len) {
        return new byte[] {(byte) ((len >>> 8) & 0xff), (byte) (len & 0xff)};
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }
}
