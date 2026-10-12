package com.redactado.noray.identity;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.NodeId;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verified certificates of the peers seen so far, and the access decision built on them. A certificate is
 * accepted only if the authority endorsed it, it names the peer that presented it (the transport already
 * authenticated that peer), and it is the newest one for that node. Validity and revocation are re-checked on
 * every request, so expiry and revocation take effect without any message from the peer.
 */
public final class PeerDirectory implements AccessPolicy {

    public enum Offer { ACCEPTED, UNCHANGED, REJECTED }

    private final TrustRoot trust;
    private final Clock clock;
    private final Map<NodeId, Certificate> certificates = new ConcurrentHashMap<>();

    public PeerDirectory(TrustRoot trust, Clock clock) {
        this.trust = Objects.requireNonNull(trust, "trust");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Verifies and stores a certificate presented by {@code from}. */
    public Offer offer(NodeId from, Certificate cert) {
        if (!cert.nodeId().equals(from)) {
            return Offer.REJECTED; // someone else's certificate: possession proves nothing
        }
        try {
            trust.verify(cert, clock.instant());
        } catch (FsException e) {
            return Offer.REJECTED;
        }
        Certificate[] replaced = new Certificate[1];
        Offer[] result = new Offer[1];
        certificates.compute(from, (id, current) -> {
            if (current != null && current.serial() >= cert.serial()) {
                result[0] = Offer.UNCHANGED;
                return current;
            }
            replaced[0] = current;
            result[0] = Offer.ACCEPTED;
            return cert;
        });
        return result[0];
    }

    @Override
    public void check(NodeId peer, Op op) throws FsException {
        Certificate cert = certificates.get(peer);
        if (cert == null) {
            throw new FsException(FsErrorCode.DENIED, "peer " + peer + " has presented no valid certificate");
        }
        if (!cert.validAt(clock.instant(), trust.skew())) {
            throw new FsException(FsErrorCode.DENIED, "certificate of " + peer + " has expired");
        }
        if (trust.isRevoked(cert)) {
            throw new FsException(FsErrorCode.DENIED, "certificate of " + peer + " was revoked");
        }
        if (!cert.allows(op)) {
            throw new FsException(FsErrorCode.DENIED, "peer " + peer + " is not allowed to " + op);
        }
    }

    public boolean knows(NodeId peer) {
        return certificates.containsKey(peer);
    }

    public Optional<Certificate> certificateOf(NodeId peer) {
        return Optional.ofNullable(certificates.get(peer));
    }

    public Map<NodeId, Certificate> all() {
        return Map.copyOf(certificates);
    }

    /** A certificate dropped by {@link #purge()}. */
    public record Removal(NodeId peer, boolean revoked) {
    }

    /** Drops certificates that are revoked or expired now and says which was which. */
    public java.util.List<Removal> purge() {
        java.util.List<Removal> removed = new java.util.ArrayList<>();
        certificates.entrySet().removeIf(e -> {
            boolean revoked = trust.isRevoked(e.getValue());
            boolean gone = revoked || !e.getValue().validAt(clock.instant(), trust.skew());
            if (gone) {
                removed.add(new Removal(e.getKey(), revoked));
            }
            return gone;
        });
        return removed;
    }
}
