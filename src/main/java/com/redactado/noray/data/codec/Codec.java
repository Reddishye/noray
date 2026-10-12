package com.redactado.noray.data.codec;

import com.redactado.noray.filesystem.api.FsException;

/**
 * A compression codec. Compression always happens on the plaintext, before encryption: ciphertext is
 * incompressible, so a storage node can never recompress what it holds.
 *
 * <p>{@link #id()} is persisted in manifests and must never change or be reused.
 */
public interface Codec {

    /** Stable wire id (0..255). */
    int id();

    String name();

    byte[] compress(byte[] plain);

    /**
     * @param expectedLength exact size of the original data; decoders must refuse to produce more than this
     *                       (protection against decompression bombs)
     */
    byte[] decompress(byte[] compressed, int expectedLength) throws FsException;
}
