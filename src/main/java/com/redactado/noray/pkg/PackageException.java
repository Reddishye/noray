package com.redactado.noray.pkg;

/** A package was refused. The reason is machine-readable so tests and tools can tell refusals apart. */
public final class PackageException extends RuntimeException {

    public enum Reason {
        /** Not a manifest: encoding, line structure, size, unknown or duplicate key, bad value. */
        MALFORMED,
        /** The publisher named in the manifest is not pinned on this node. */
        UNPINNED_PUBLISHER,
        /** Fewer valid signatures by the publisher's keys than its threshold. */
        BAD_SIGNATURE,
        /** The payload differs from the hash (or size) the manifest commits to. */
        PAYLOAD_MISMATCH,
        /** Built for a module API this node does not provide. */
        INCOMPATIBLE_API,
        /** A lower version than one already installed. */
        DOWNGRADE,
        /** The package asks for something it cannot have (e.g. a service grant for a process package). */
        FORBIDDEN_REQUEST,
        /** Approval wider than what the package requested. */
        GRANT_EXCEEDS_REQUEST,
        /** Not installed, not approved, or a dependency is missing. */
        NOT_FOUND,
        /** An operation that conflicts with the current state (e.g. removing the active version). */
        CONFLICT,
        /** Reading or writing the package store failed. */
        IO
    }

    private final Reason reason;

    public PackageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PackageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    @Override
    public String getMessage() {
        return reason.name().toLowerCase().replace('_', ' ') + ": " + super.getMessage();
    }
}
