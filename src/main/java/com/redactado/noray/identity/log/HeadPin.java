package com.redactado.noray.identity.log;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;

/**
 * The newest entry a reader has seen: sequence number and hash. Keeping it where the log's own storage cannot
 * reach (another node, a sealed file) makes rollback visible: a log that is shorter than the pin, or has a
 * different entry at that position, is refused. A pin alone does not stop someone who can rewrite both the log and
 * the pin, which is why pins are meant to be compared between nodes.
 */
public record HeadPin(long seq, byte[] hash) {

    public HeadPin {
        if (hash.length != 32) {
            throw new IllegalArgumentException("hash must be 32 bytes");
        }
        hash = hash.clone();
    }

    /** @throws FsException {@code DENIED} when {@code log} is behind this pin or contradicts it */
    public void check(SignedLog log) throws FsException {
        if (log.size() <= seq) {
            throw new FsException(FsErrorCode.DENIED,
                    "log was rolled back: it ends at entry " + (log.size() - 1) + " but entry " + seq + " was already seen");
        }
        if (!Arrays.equals(log.entry((int) seq).hash(), hash)) {
            throw new FsException(FsErrorCode.DENIED, "log was rewritten: entry " + seq + " is not the one seen before");
        }
    }

    /** Whether {@code other} is at least as new. */
    public boolean isNewerThan(HeadPin other) {
        return seq > other.seq;
    }

    public void save(Path file) throws FsException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            SecureFiles.createOwnerOnly(tmp);
            Files.write(tmp, ByteBuffer.allocate(40).putLong(seq).put(hash).array());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot save the head pin", e);
        }
    }

    public static Optional<HeadPin> load(Path file) throws FsException {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            byte[] raw = Files.readAllBytes(file);
            if (raw.length != 40) {
                throw new FsException(FsErrorCode.DENIED, "head pin file is corrupt");
            }
            ByteBuffer b = ByteBuffer.wrap(raw);
            long seq = b.getLong();
            byte[] h = new byte[32];
            b.get(h);
            return Optional.of(new HeadPin(seq, h));
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot read the head pin", e);
        }
    }
}
