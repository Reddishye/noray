package com.redactado.noray.identity;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** An Ed25519 signing key. The 32-byte seed is the secret; {@link #publicKey()} identifies the holder. */
public final class SigningKey {

    private final byte[] seed;
    private final byte[] publicKey;

    private SigningKey(byte[] seed) {
        this.seed = seed;
        this.publicKey = Ed25519.publicKey(seed);
    }

    public static SigningKey generate() {
        byte[] seed = new byte[Ed25519.KEY_SIZE];
        new SecureRandom().nextBytes(seed);
        return new SigningKey(seed);
    }

    public static SigningKey fromSeed(byte[] seed) {
        Objects.requireNonNull(seed, "seed");
        if (seed.length != Ed25519.KEY_SIZE) {
            throw new IllegalArgumentException("a signing seed is 32 bytes");
        }
        return new SigningKey(seed.clone());
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    public String publicKeyHex() {
        return HexFormat.of().formatHex(publicKey);
    }

    /** The secret. Handle with care: whoever holds it can sign as this key. */
    public byte[] seed() {
        return seed.clone();
    }

    public byte[] sign(String domain, byte[] message) {
        return Ed25519.sign(seed, domain, message);
    }

    public static boolean verify(byte[] publicKey, String domain, byte[] message, byte[] signature) {
        return Ed25519.verify(publicKey, domain, message, signature);
    }

    /** Overwrites the secret in memory (best effort: copies made earlier are not reachable). */
    public void destroy() {
        Arrays.fill(seed, (byte) 0);
    }

    @Override
    public String toString() {
        return "SigningKey[" + publicKeyHex().substring(0, 12) + "…]";
    }
}
