package com.redactado.noray.net;

/**
 * Logical mesh channels. Each channel has a stable {@link #wireId()} that is used on the wire,
 * so reordering or inserting enum constants never changes the protocol. Wire ids must never be
 * reused or changed once released.
 */
public enum MeshChannel {
    CTRL(0),
    FS_BLOB(1),
    FS_REPAIR(2),
    FS_REF(3),
    /** Certificates and revocation lists (see {@code identity.CertificateExchange}). */
    IDENTITY(4),
    /** Signed checkpoint logs kept by other nodes (see {@code identity.witness.WitnessService}). */
    WITNESS(5),
    /** Sealed namespace snapshots kept for other nodes (see {@code filesystem.vfs.journal.NamespaceVault}). */
    NAMESPACE(6),
    /** Onion cells and key announcements (see {@code net.onion.MeshOnionTransport}). */
    ONION(7),
    /** Signed packages fetched by payload hash between one's own nodes (see {@code pkg.PackageExchange}). */
    PACKAGES(8);

    private final int wireId;

    MeshChannel(int wireId) {
        this.wireId = wireId;
    }

    /** Stable identifier (0..255) used in the wire format. */
    public int wireId() {
        return wireId;
    }

    /** Resolves a channel from its wire id, or {@code null} if unknown. */
    public static MeshChannel fromWireId(int wireId) {
        for (MeshChannel c : values()) {
            if (c.wireId == wireId) {
                return c;
            }
        }
        return null;
    }
}
