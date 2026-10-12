package com.redactado.noray.filesystem.store;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class LocalDirObjectStore implements ObjectStore {
    private final Path objectsDir;

    public LocalDirObjectStore(Path root) {
        Objects.requireNonNull(root, "root");
        this.objectsDir = root.resolve("objects");
        try {
            Files.createDirectories(objectsDir);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Failed to create objects directory: " + objectsDir, e);
        }
    }

    private Path objectPath(ObjectId id) {
        String hex = id.hex();
        String prefix = hex.substring(0, 2);
        String suffix = hex.substring(2);
        return objectsDir.resolve(prefix).resolve(suffix);
    }

    @Override
    public PutResult put(byte[] data) throws FsException {
        Objects.requireNonNull(data, "data");
        ObjectId id = ContentDigest.sha256(data);
        Path targetPath = objectPath(id);

        if (Files.exists(targetPath)) {
            return new PutResult(id, data.length, false);
        }

        try {
            Path parentDir = targetPath.getParent();
            Files.createDirectories(parentDir);

            Path tempFile = Files.createTempFile(parentDir, "put-", ".tmp");
            try (FileChannel ch = FileChannel.open(tempFile, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(data);
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true); // the bytes are on disk before the name is
            }

            try {
                Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            syncDirectory(parentDir); // and the name survives a crash
            return new PutResult(id, data.length, true);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Failed to put object " + id, e);
        }
    }

    /** Makes a new directory entry durable; platforms that cannot open a directory skip it. */
    private static void syncDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | RuntimeException ignored) {
            // not supported here (Windows) or not permitted: the file itself was synced
        }
    }

    @Override
    public byte[] get(ObjectId id) throws FsException {
        Objects.requireNonNull(id, "id");
        Path targetPath = objectPath(id);
        if (!Files.exists(targetPath)) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Object not found: " + id);
        }

        byte[] data;
        try {
            data = Files.readAllBytes(targetPath);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Failed to read object " + id, e);
        }

        ObjectId verified = ContentDigest.sha256(data);
        if (!verified.equals(id)) {
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ignored) {}
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "Digest mismatch for object " + id);
        }

        return data;
    }

    @Override
    public boolean has(ObjectId id) {
        Objects.requireNonNull(id, "id");
        return Files.exists(objectPath(id));
    }

    @Override
    public void delete(ObjectId id) throws FsException {
        Objects.requireNonNull(id, "id");
        Path targetPath = objectPath(id);
        if (!Files.exists(targetPath)) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Object not found: " + id);
        }
        try {
            Files.delete(targetPath);
            syncDirectory(targetPath.getParent());
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Failed to delete object " + id, e);
        }
    }

    @Override
    public long size(ObjectId id) throws FsException {
        Objects.requireNonNull(id, "id");
        Path targetPath = objectPath(id);
        if (!Files.exists(targetPath)) {
            throw new FsException(FsErrorCode.NOT_FOUND, "Object not found: " + id);
        }
        try {
            return Files.size(targetPath);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Failed to get size for object " + id, e);
        }
    }

    @Override
    public Set<ObjectId> listIds() {
        if (!Files.exists(objectsDir)) {
            return Set.of();
        }
        Set<ObjectId> ids = new HashSet<>();
        try (Stream<Path> prefixes = Files.list(objectsDir)) {
            for (Path prefixPath : prefixes.toList()) {
                if (Files.isDirectory(prefixPath)) {
                    String prefix = prefixPath.getFileName().toString();
                    try (Stream<Path> files = Files.list(prefixPath)) {
                        for (Path file : files.toList()) {
                            if (Files.isRegularFile(file)) {
                                String suffix = file.getFileName().toString();
                                try {
                                    ids.add(new ObjectId(prefix + suffix));
                                } catch (IllegalArgumentException ignored) {}
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Failed to list objects in " + objectsDir, e);
        }
        return ids;
    }
}
