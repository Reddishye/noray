package com.redactado.noray.data.crypto;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Small files (statistics, caches) encrypted with a key from the {@link Keyring} and replaced atomically. */
public final class EncryptedFile {

    private EncryptedFile() {
    }

    public static void write(Path file, byte[] key, String label, byte[] data) throws FsException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            SecureFiles.createOwnerOnly(tmp);
            Files.write(tmp, DataCrypto.sealRandomNonce(key, label.getBytes(StandardCharsets.UTF_8), data));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot write " + file, e);
        }
    }

    /** Empty if the file does not exist; DIGEST_MISMATCH if it was tampered with or the key is wrong. */
    public static Optional<byte[]> read(Path file, byte[] key, String label) throws FsException {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(DataCrypto.openRandomNonce(key, label.getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot read " + file, e);
        }
    }
}
