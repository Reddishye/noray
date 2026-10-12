package com.redactado.noray.identity;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.charset.StandardCharsets;

/** Ed25519 with mandatory domain separation: a signature made for one purpose never verifies for another. */
final class Ed25519 {

    static final int KEY_SIZE = 32;
    static final int SIG_SIZE = 64;

    private Ed25519() {
    }

    static byte[] sign(byte[] seed, String domain, byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(seed, 0));
        byte[] m = framed(domain, message);
        signer.update(m, 0, m.length);
        return signer.generateSignature();
    }

    static boolean verify(byte[] publicKey, String domain, byte[] message, byte[] signature) {
        if (publicKey == null || publicKey.length != KEY_SIZE || signature == null || signature.length != SIG_SIZE) {
            return false;
        }
        try {
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
            byte[] m = framed(domain, message);
            signer.update(m, 0, m.length);
            return signer.verifySignature(signature);
        } catch (RuntimeException e) {
            return false; // malformed key
        }
    }

    static byte[] publicKey(byte[] seed) {
        return new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded();
    }

    private static byte[] framed(String domain, byte[] message) {
        byte[] d = domain.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + d.length + message.length];
        out[0] = (byte) d.length;
        System.arraycopy(d, 0, out, 1, d.length);
        System.arraycopy(message, 0, out, 1 + d.length, message.length);
        return out;
    }
}
