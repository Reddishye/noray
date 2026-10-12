package com.redactado.noray.net.onion;

import com.redactado.noray.net.overlay.NoiseCrypto;

import java.util.Arrays;

/**
 * The key a node uses to peel onion layers addressed to it. It is meant to be short-lived (a fresh one at every
 * start): relays that forget it can never decrypt recorded cells later, which is the forward secrecy of the layer.
 */
public final class OnionIdentity {

    private final byte[] privateKey;
    private final byte[] publicKey;

    private OnionIdentity(byte[] privateKey) {
        this.privateKey = privateKey;
        this.publicKey = NoiseCrypto.x25519Public(privateKey);
    }

    public static OnionIdentity generate() {
        return new OnionIdentity(NoiseCrypto.x25519GeneratePrivate());
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    byte[] dh(byte[] peerPublic) {
        return NoiseCrypto.dh(privateKey, peerPublic);
    }

    /** Wipes the private key. */
    public void destroy() {
        Arrays.fill(privateKey, (byte) 0);
    }
}
