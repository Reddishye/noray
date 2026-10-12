package com.redactado.noray.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Helpers for creating files that only their owner can read or write. */
public final class SecureFiles {

    private SecureFiles() {
    }

    /** Creates a new empty file with mode 0600 (best effort on non-POSIX filesystems). */
    public static void createOwnerOnly(Path file) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
            File f = file.toFile();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        }
    }

    /** Restricts an existing file to its owner (best effort, never throws). */
    public static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // best effort
        }
    }
}
