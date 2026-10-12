package com.redactado.noray.identity;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** This node's identity, the certificate it presents, and what it trusts. The operator-facing side of {@code identity}. */
public final class IdentityService {

    private final NodeId nodeId;
    private final byte[] transportKey;
    private final byte[] kemKey;
    private final PeerEnroller enroller;
    private final NodeIdentity identity;
    private final TrustRoot trust;
    private final PeerDirectory directory;
    private final Path certFile;
    private final Clock clock;
    private volatile Certificate own;
    private volatile CertificateExchange exchange;

    public IdentityService(NodeId nodeId, byte[] transportKey, NodeIdentity identity, TrustRoot trust,
                           PeerDirectory directory, Path certFile, Clock clock) {
        this(nodeId, transportKey, null, identity, trust, directory, certFile, clock, PeerEnroller.NONE);
    }

    public IdentityService(NodeId nodeId, byte[] transportKey, byte[] kemKey, NodeIdentity identity, TrustRoot trust,
                           PeerDirectory directory, Path certFile, Clock clock, PeerEnroller enroller) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.enroller = Objects.requireNonNull(enroller, "enroller");
        this.kemKey = kemKey == null ? new byte[0] : kemKey.clone();
        this.transportKey = transportKey == null ? new byte[0] : transportKey.clone();
        this.identity = Objects.requireNonNull(identity, "identity");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.certFile = Objects.requireNonNull(certFile, "certFile");
        this.clock = Objects.requireNonNull(clock, "clock");
        loadCertificate();
    }

    void attach(CertificateExchange exchange) {
        this.exchange = exchange;
    }

    private void loadCertificate() {
        if (!Files.isRegularFile(certFile)) {
            return;
        }
        try {
            Certificate c = Certificate.parse(Files.readString(certFile));
            if (matchesThisNode(c)) {
                own = c; // expiry is reported by status(), not hidden by refusing to load
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("cannot read own certificate " + certFile, e);
        }
    }

    private boolean matchesThisNode(Certificate c) {
        return c.nodeId().equals(nodeId) && Arrays.equals(c.signKey(), identity.publicKey());
    }

    public NodeId nodeId() { return nodeId; }
    public TrustRoot trust() { return trust; }
    public PeerDirectory directory() { return directory; }
    public NodeIdentity identity() { return identity; }
    public Optional<Certificate> certificate() { return Optional.ofNullable(own); }

    /** What the operator carries to the authority to have this node admitted. */
    public EnrollmentRequest enrollmentRequest() {
        return new EnrollmentRequest(nodeId, identity.publicKey(), transportKey, kemKey);
    }

    /** Installs the certificate the authority issued for this node, after verifying it fully. */
    public void install(Certificate cert) throws FsException {
        if (!matchesThisNode(cert)) {
            throw new FsException(FsErrorCode.DENIED, "this certificate is for another node or another key");
        }
        if (transportKey.length != 0 && !Arrays.equals(cert.transportKey(), transportKey)) {
            throw new FsException(FsErrorCode.DENIED, "this certificate names a different transport key");
        }
        if (kemKey.length != 0 && !Arrays.equals(cert.kemKey(), kemKey)) {
            throw new FsException(FsErrorCode.DENIED, "this certificate names a different post-quantum key");
        }
        trust.verify(cert, clock.instant());
        Certificate current = own;
        if (current != null && current.serial() > cert.serial()) {
            throw new FsException(FsErrorCode.DENIED, "a newer certificate is already installed");
        }
        try {
            Files.createDirectories(certFile.getParent());
            Path tmp = certFile.resolveSibling(certFile.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            SecureFiles.createOwnerOnly(tmp);
            Files.writeString(tmp, cert.toText() + "\n");
            Files.move(tmp, certFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot store the certificate: " + e.getMessage(), e);
        }
        own = cert;
        CertificateExchange ex = exchange;
        if (ex != null) {
            ex.announceAll();
        }
    }

    /**
     * Admits a peer from its certificate: verifies it, makes the peer reachable with the keys it names and
     * introduces this node to it. One signed object replaces entering node id and keys by hand.
     */
    public void enroll(Certificate cert, String introHint) throws FsException {
        if (cert.nodeId().equals(nodeId)) {
            throw new FsException(FsErrorCode.DENIED, "that is this node's own certificate (use install)");
        }
        trust.verify(cert, clock.instant());
        if (directory.offer(cert.nodeId(), cert) == PeerDirectory.Offer.REJECTED) {
            throw new FsException(FsErrorCode.DENIED, "certificate rejected");
        }
        enroller.enroll(cert, introHint == null ? "" : introHint);
        CertificateExchange ex = exchange;
        if (ex != null) {
            ex.announce(cert.nodeId());
        }
    }

    private final java.util.List<java.util.function.Consumer<NodeId>> revokedListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Called with every peer that stops being trusted because of a revocation (services drop what they keep for it). */
    public void onRevoked(java.util.function.Consumer<NodeId> listener) {
        revokedListeners.add(listener);
    }

    private void notifyRevoked(NodeId peer) {
        for (var l : revokedListeners) {
            try {
                l.accept(peer);
            } catch (RuntimeException e) {
                System.getLogger("noray.identity").log(System.Logger.Level.WARNING, "revocation listener: " + e.getMessage());
            }
        }
    }

    /**
     * Housekeeping: forgets certificates that expired or were revoked, and closes the transport door on revoked
     * peers (an expired peer stays reachable so that it can present a renewed certificate). Returns the peers removed.
     */
    public java.util.List<NodeId> purge() {
        java.util.List<NodeId> removed = new java.util.ArrayList<>();
        for (PeerDirectory.Removal r : directory.purge()) {
            removed.add(r.peer());
            if (r.revoked()) {
                enroller.unenroll(r.peer());
                notifyRevoked(r.peer());
            }
        }
        return removed;
    }

    /**
     * Takes in a revocation list (from the operator or by gossip): verifies it, forgets the certificates it voids
     * and closes the transport door on every node it newly names. Returns false if it was not newer.
     */
    public boolean acceptRevocations(RevocationList list) throws FsException {
        RevocationList previous = trust.revocations();
        boolean accepted = trust.acceptRevocations(list);
        if (accepted) {
            purge();
            for (java.util.Map.Entry<NodeId, Long> e : list.entries().entrySet()) {
                Long before = previous.entries().get(e.getKey());
                if ((before == null || e.getValue() > before) && !e.getKey().equals(nodeId)) {
                    enroller.unenroll(e.getKey());
                    notifyRevoked(e.getKey());
                }
            }
        }
        return accepted;
    }

    /** {@link #acceptRevocations} and then spread the list to the peers. */
    public boolean applyRevocations(RevocationList list) throws FsException {
        boolean accepted = acceptRevocations(list);
        if (accepted) {
            CertificateExchange ex = exchange;
            if (ex != null) {
                ex.gossipRevocations(null);
            }
        }
        return accepted;
    }
}
