package com.redactado.noray.filesystem.api;

import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.vfs.VfsPath;

import java.util.List;

public interface FileSystemService {
    void mkdir(VfsPath path) throws FsException;
    void createFile(VfsPath path) throws FsException;
    void unlink(VfsPath path) throws FsException;
    DirectoryEntry stat(VfsPath path) throws FsException;
    List<DirectoryEntry> readdir(VfsPath path) throws FsException;
    ObjectId writeFully(VfsPath path, byte[] data) throws FsException; // replace file blob
    byte[] readFully(VfsPath path) throws FsException;

    /**
     * An opaque token that changes whenever the file's content is replaced ({@code ""} for a file never written).
     * Pass it to {@link #writeIfVersion} to update the file only if nobody else did in between.
     */
    default String version(VfsPath path) throws FsException {
        throw new FsException(FsErrorCode.UNSUPPORTED, "versions are not supported by this filesystem view");
    }

    /**
     * Replaces the content only if the file is still at {@code expectedVersion}; otherwise fails with
     * {@link FsErrorCode#CONFLICT} and changes nothing. The check and the replacement are atomic.
     */
    default ObjectId writeIfVersion(VfsPath path, String expectedVersion, byte[] data) throws FsException {
        throw new FsException(FsErrorCode.UNSUPPORTED, "versions are not supported by this filesystem view");
    }

    /** True once the file has been given content (an empty write counts). Used to enforce write-once grants. */
    default boolean hasContent(VfsPath path) throws FsException {
        return readFully(path).length > 0;
    }
}
