package com.redactado.noray.net.overlay;

import org.bouncycastle.crypto.digests.Blake2sDigest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.rfc7748.X25519;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Noise primitives: BLAKE2s, HMAC-BLAKE2s, HKDF, ChaCha20-Poly1305, X25519.
 * HASHLEN=32, DHLEN=32. See noiseprotocol.org/noise.html sections 4–5.
 */
public final class NoiseCrypto {

    public static final int HASHLEN = 32;
    public static final int DHLEN = 32;
    public static final int TAGLEN = 16;
    public static final String PROTOCOL = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s";
    public static final byte[] PROLOGUE = "noray-net-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static final SecureRandom RNG = new SecureRandom();

    private NoiseCrypto() {}

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] hash(byte[] data) {
        Blake2sDigest d = new Blake2sDigest(256);
        d.update(data, 0, data.length);
        byte[] out = new byte[HASHLEN];
        d.doFinal(out, 0);
        return out;
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        HMac mac = new HMac(new Blake2sDigest(256));
        mac.init(new KeyParameter(key));
        mac.update(data, 0, data.length);
        byte[] out = new byte[HASHLEN];
        mac.doFinal(out, 0);
        return out;
    }

    public static byte[][] hkdf(byte[] chainingKey, byte[] ikm, int outputs) {
        byte[] temp = hmac(chainingKey, ikm == null ? new byte[0] : ikm);
        byte[][] out = new byte[outputs][];
        byte[] prev = new byte[0];
        for (int i = 0; i < outputs; i++) {
            byte[] input = Arrays.copyOf(prev, prev.length + 1);
            input[input.length - 1] = (byte) (i + 1);
            prev = hmac(temp, input);
            out[i] = prev;
        }
        return out;
    }

    public static byte[] mixKey(byte[] ck, byte[] ikm) {
        return hkdf(ck, ikm, 2)[0];
    }

    public static byte[] mixHash(byte[] h, byte[] data) {
        byte[] cat = new byte[h.length + data.length];
        System.arraycopy(h, 0, cat, 0, h.length);
        System.arraycopy(data, 0, cat, h.length, data.length);
        return hash(cat);
    }

    public static byte[] x25519Public(byte[] priv) {
        byte[] pub = new byte[32];
        X25519.generatePublicKey(priv, 0, pub, 0);
        return pub;
    }

    public static byte[] x25519GeneratePrivate() {
        byte[] priv = new byte[32];
        X25519.generatePrivateKey(RNG, priv);
        return priv;
    }

    public static byte[] dh(byte[] priv, byte[] pub) {
        byte[] out = new byte[32];
        if (!X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) {
            throw new IllegalStateException("X25519 agreement failed");
        }
        return out;
    }

    public static byte[] nonce12(long n) {
        byte[] nonce = new byte[12];
        ByteBuffer.wrap(nonce, 4, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(n);
        return nonce;
    }

    public static byte[] encrypt(byte[] key, long nonce, byte[] ad, byte[] plaintext) {
        ChaCha20Poly1305 aead = new ChaCha20Poly1305();
        aead.init(true, new AEADParameters(new KeyParameter(key), TAGLEN * 8, nonce12(nonce), ad));
        byte[] out = new byte[aead.getOutputSize(plaintext.length)];
        int len = aead.processBytes(plaintext, 0, plaintext.length, out, 0);
        try {
            len += aead.doFinal(out, len);
        } catch (Exception e) {
            throw new IllegalStateException("AEAD encrypt failed", e);
        }
        if (len != out.length) {
            return Arrays.copyOf(out, len);
        }
        return out;
    }

    public static byte[] decrypt(byte[] key, long nonce, byte[] ad, byte[] ciphertext) {
        ChaCha20Poly1305 aead = new ChaCha20Poly1305();
        aead.init(false, new AEADParameters(new KeyParameter(key), TAGLEN * 8, nonce12(nonce), ad));
        byte[] out = new byte[aead.getOutputSize(ciphertext.length)];
        try {
            int len = aead.processBytes(ciphertext, 0, ciphertext.length, out, 0);
            len += aead.doFinal(out, len);
            return Arrays.copyOf(out, len);
        } catch (Exception e) {
            throw new IllegalStateException("AEAD decrypt failed", e);
        }
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    public static byte[] protocolNameHash() {
        byte[] name = PROTOCOL.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (name.length == HASHLEN) return name;
        return hash(name);
    }
}
