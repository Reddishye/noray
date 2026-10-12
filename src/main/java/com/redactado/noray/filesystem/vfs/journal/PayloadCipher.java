package com.redactado.noray.filesystem.vfs.journal;

import com.redactado.noray.data.crypto.DataCrypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encrypts journal records. {@code sequence} is the zero-based position of the record in the file and is
 * authenticated, so records cannot be reordered, duplicated or moved to another position without detection.
 * (Dropping records from the very end of the file cannot be detected without an external anchor.)
 */
public interface PayloadCipher {

    byte[] seal(byte[] plain, long sequence);

    /** @throws com.redactado.noray.filesystem.api.FsException DIGEST_MISMATCH on a wrong key or tampering */
    byte[] open(byte[] sealed, long sequence);

    /** ChaCha20-Poly1305 with a random nonce per record under {@code key}. */
    static PayloadCipher aead(byte[] key) {
        Objects.requireNonNull(key, "key");
        byte[] k = key.clone();
        return new PayloadCipher() {
            private byte[] aad(long sequence) {
                byte[] label = "noray/journal/v2|".getBytes(StandardCharsets.UTF_8);
                return ByteBuffer.allocate(label.length + 8).put(label).putLong(sequence).array();
            }

            @Override
            public byte[] seal(byte[] plain, long sequence) {
                return DataCrypto.sealRandomNonce(k, aad(sequence), plain);
            }

            @Override
            public byte[] open(byte[] sealed, long sequence) {
                return DataCrypto.openRandomNonce(k, aad(sequence), sealed);
            }
        };
    }
}
