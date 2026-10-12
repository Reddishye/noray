package com.redactado.noray.pkg;

import com.redactado.noray.kernel.capability.Capability;
import com.redactado.noray.pkg.PackageException.Reason;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * Decides whether a manifest and payload may be installed or loaded, against the publishers pinned right now. Run at
 * install, at approval, and again every time a package is loaded, so unpinning a publisher or corrupting a stored file
 * stops the package at its next load.
 */
public final class PackageVerifier {

    /** A process package only talks to the filesystem bridge; anything else it asks for could never be honoured. */
    private static final Set<String> PROCESS_KINDS = Set.of("fs.read", "fs.write", "fs.create", "fs.delete");

    public record Verified(PackageManifest manifest, Publisher publisher) {
    }

    private PackageVerifier() {
    }

    public static Verified verify(byte[] manifestBytes, byte[] payload, PublisherRegistry trust) {
        PackageManifest m = PackageManifest.parse(manifestBytes);
        Publisher publisher = trust.get(m.publisher()).orElseThrow(() -> new PackageException(Reason.UNPINNED_PUBLISHER,
                "publisher '" + m.publisher() + "' is not pinned on this node (pkg publisher add)"));
        int valid = publisher.validSignatures(m);
        if (valid < publisher.threshold()) {
            throw new PackageException(Reason.BAD_SIGNATURE, m + ": " + valid + " valid signature(s) by '" + publisher.name()
                    + "', " + publisher.threshold() + " needed");
        }
        if (m.api() > PackageManifest.NODE_API || m.api() < PackageManifest.MIN_API) {
            throw new PackageException(Reason.INCOMPATIBLE_API, m + " is built for module API " + m.api()
                    + "; this node supports " + PackageManifest.MIN_API + ".." + PackageManifest.NODE_API);
        }
        if (m.sandbox() == PackageManifest.Sandboxing.JVM && !publisher.trusted()) {
            throw new PackageException(Reason.FORBIDDEN_REQUEST, m + " wants to run inside the node's JVM, but publisher '"
                    + publisher.name() + "' is not marked trusted");
        }
        if (m.sandbox() == PackageManifest.Sandboxing.PROCESS) {
            for (String r : m.requests()) {
                if (!PROCESS_KINDS.contains(Capability.parse(r).kind())) {
                    throw new PackageException(Reason.FORBIDDEN_REQUEST, m + " runs in the process sandbox, which can only "
                            + "grant filesystem access; it asks for " + r);
                }
            }
        }
        if (payload.length != m.payloadSize()) {
            throw new PackageException(Reason.PAYLOAD_MISMATCH, m + ": payload is " + payload.length + " bytes, manifest says "
                    + m.payloadSize());
        }
        byte[] expected = HexFormat.of().parseHex(m.payloadSha256());
        if (!MessageDigest.isEqual(expected, sha256(payload))) {
            throw new PackageException(Reason.PAYLOAD_MISMATCH, m + ": payload hash differs from the signed manifest");
        }
        return new Verified(m, publisher);
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
