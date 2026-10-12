package com.redactado.noray.net.overlay;

import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.security.SecureRandom;

/**
 * Makes every datagram look like random bytes: {@code salt(12) || ChaCha20(key, salt, datagram)}. The key comes from
 * the mesh pre-shared key, so whoever does not hold it finds no version byte, no type, no fixed length pattern to
 * recognise the protocol by. It is not a security layer (the sessions underneath are) and gives nothing against
 * someone who has the key; it only removes the fingerprint.
 */
final class Obfuscation {

    static final int SALT = 12;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    Obfuscation(byte[] psk) {
        this.key = NoiseCrypto.hkdf(psk, "noray/obfuscation/v1".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1)[0];
    }

    byte[] wrap(byte[] datagram) {
        byte[] out = new byte[SALT + datagram.length];
        byte[] salt = new byte[SALT];
        random.nextBytes(salt);
        System.arraycopy(salt, 0, out, 0, SALT);
        apply(salt, datagram, 0, out, SALT, datagram.length);
        return out;
    }

    /** @return the datagram, or null when it is too short to be one of ours */
    byte[] unwrap(byte[] wire) {
        if (wire.length <= SALT) return null;
        byte[] salt = java.util.Arrays.copyOf(wire, SALT);
        byte[] out = new byte[wire.length - SALT];
        apply(salt, wire, SALT, out, 0, out.length);
        return out;
    }

    private void apply(byte[] salt, byte[] in, int inOff, byte[] out, int outOff, int len) {
        ChaCha7539Engine engine = new ChaCha7539Engine();
        engine.init(true, new ParametersWithIV(new KeyParameter(key), salt));
        engine.processBytes(in, inOff, len, out, outOff);
    }
}
