package com.redactado.noray.filesystem.api;

/** Sent between nodes by ordinal: only ever append new codes at the end. */
public enum FsErrorCode {
    NOT_FOUND,
    ALREADY_EXISTS,
    NOT_A_DIRECTORY,
    IS_DIRECTORY,
    VOLUME_UNAVAILABLE,
    VOLUME_UNKNOWN,
    QUORUM_FAILED,
    DIGEST_MISMATCH,
    INVALID_PATH,
    READ_ONLY,
    CONFLICT,
    DENIED,
    QUOTA_EXCEEDED,
    INTERNAL,
    /** The operation is not available through this view of the filesystem. */
    UNSUPPORTED
}
