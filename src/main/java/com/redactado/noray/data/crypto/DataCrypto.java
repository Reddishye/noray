package com.redactado.noray.data.crypto;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Symmetric primitives for stored data: ChaCha20-Poly1305 (AEAD) with one-time keys derived by HKDF-SHA256.
 *
 * <p>Every sealed blob uses its own key (derived from the file key and a unique context string) and
 * therefore a constant all-zero nonce is safe: a key is never used to encrypt twice.
 */
public final class DataCrypto {

    public static final int KEY_SIZE = 32;
    public static final int TAG_SIZE = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] ZERO_NONCE = new byte[12];

    private DataCrypto() {
    }

    public static byte[] randomKey() {
        byte[] k = new byte[KEY_SIZE];
        RANDOM.nextBytes(k);
        return k;
    }

    /** HKDF-Expand (RFC 5869) with SHA-256, producing one 32-byte block. {@code parentKey} must be uniformly random. */
    public static byte[] derive(byte[] parentKey, String context) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(parentKey, "HmacSHA256"));
            mac.update(context.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 1);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Encrypts and authenticates. {@code key} must be single-use (see class doc). */
    public static byte[] seal(byte[] key, byte[] aad, byte[] plaintext) {
        try {
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(ZERO_NONCE));
            c.updateAAD(aad);
            return c.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new FsException(FsErrorCode.INTERNAL, "encryption failed", e);
        }
    }

    /** Verifies and decrypts. Any tampering (data, aad or key) is reported as DIGEST_MISMATCH. */
    public static byte[] open(byte[] key, byte[] aad, byte[] ciphertext) {
        try {
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(ZERO_NONCE));
            c.updateAAD(aad);
            return c.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "authentication failed", e);
        }
    }

    public static final int NONCE_SIZE = 12;

    /**
     * Like {@link #seal} for a key that encrypts <em>many</em> messages (journals, caches): a random 96-bit nonce
     * is generated per message and prepended to the output. Safe for up to about 2^32 messages per key.
     */
    public static byte[] sealRandomNonce(byte[] key, byte[] aad, byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_SIZE];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
            c.updateAAD(aad);
            byte[] ct = c.doFinal(plaintext);
            byte[] out = new byte[NONCE_SIZE + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_SIZE);
            System.arraycopy(ct, 0, out, NONCE_SIZE, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new FsException(FsErrorCode.INTERNAL, "encryption failed", e);
        }
    }

    /** Inverse of {@link #sealRandomNonce}; any tampering is reported as DIGEST_MISMATCH. */
    public static byte[] openRandomNonce(byte[] key, byte[] aad, byte[] sealed) {
        if (sealed.length < NONCE_SIZE + TAG_SIZE) {
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "sealed message too short");
        }
        try {
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                    new IvParameterSpec(java.util.Arrays.copyOf(sealed, NONCE_SIZE)));
            c.updateAAD(aad);
            return c.doFinal(sealed, NONCE_SIZE, sealed.length - NONCE_SIZE);
        } catch (GeneralSecurityException e) {
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "authentication failed", e);
        }
    }
}
