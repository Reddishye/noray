package com.redactado.noray.identity;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The keys this node trusts to say who belongs to the cluster, how many of them must agree, and the newest
 * revocation list seen. Nothing here is secret. Authority private keys do not live on cluster nodes: they stay
 * offline (or split across people with a threshold), so taking over a node never yields the power to admit others.
 */
public final class TrustRoot {

    public static final Duration DEFAULT_SKEW = Duration.ofMinutes(5);

    private final List<byte[]> authorities;
    private final int threshold;
    private final Duration skew;
    private final Path revocationFile;
    private volatile RevocationList revocations = RevocationList.empty();

    public TrustRoot(Collection<byte[]> authorities, int threshold, Duration skew, Path revocationFile) {
        if (authorities.isEmpty()) {
            throw new IllegalArgumentException("a trust root needs at least one authority key");
        }
        if (threshold < 1 || threshold > authorities.size()) {
            throw new IllegalArgumentException("threshold must be between 1 and the number of authorities");
        }
        List<byte[]> copy = new ArrayList<>();
        for (byte[] a : authorities) {
            if (a.length != Ed25519.KEY_SIZE) {
                throw new IllegalArgumentException("authority keys are 32 bytes");
            }
            if (copy.stream().noneMatch(x -> java.util.Arrays.equals(x, a))) {
                copy.add(a.clone());
            }
        }
        if (threshold > copy.size()) {
            throw new IllegalArgumentException("threshold exceeds the number of distinct authority keys");
        }
        this.authorities = List.copyOf(copy);
        this.threshold = threshold;
        this.skew = Objects.requireNonNull(skew, "skew");
        this.revocationFile = revocationFile;
        loadRevocations();
    }

    public TrustRoot(Collection<byte[]> authorities, int threshold) {
        this(authorities, threshold, DEFAULT_SKEW, null);
    }

    public int threshold() { return threshold; }
    public int authorityCount() { return authorities.size(); }
    public List<byte[]> authorities() { return authorities.stream().map(byte[]::clone).toList(); }
    public Duration skew() { return skew; }
    public RevocationList revocations() { return revocations; }

    /** Checks signatures, validity window and revocation. @throws FsException {@code DENIED} with the reason */
    public void verify(Certificate cert, Instant now) throws FsException {
        int valid = Endorsement.countValid(authorities, Certificate.DOMAIN, cert.body(), cert.endorsements());
        if (valid < threshold) {
            throw denied("certificate is endorsed by " + valid + " trusted authorities, " + threshold + " required");
        }
        if (!cert.validAt(now, skew)) {
            throw denied("certificate is not valid now (" + cert.notBefore() + " .. " + cert.notAfter() + ")");
        }
        if (revocations.revokes(cert)) {
            throw denied("certificate was revoked");
        }
    }

    public boolean isRevoked(Certificate cert) {
        return revocations.revokes(cert);
    }

    /**
     * Accepts a newer revocation list. Returns {@code false} when it is not newer (nothing to do).
     *
     * @throws FsException {@code DENIED} when it is not endorsed enough or drops an earlier revocation
     */
    public synchronized boolean acceptRevocations(RevocationList list) throws FsException {
        if (list.version() <= revocations.version()) {
            return false;
        }
        int valid = Endorsement.countValid(authorities, RevocationList.DOMAIN, list.body(), list.endorsements());
        if (valid < threshold) {
            throw denied("revocation list is endorsed by " + valid + " trusted authorities, " + threshold + " required");
        }
        if (!list.covers(revocations)) {
            throw denied("revocation list would un-revoke a node");
        }
        saveRevocations(list);
        revocations = list;
        return true;
    }

    private void saveRevocations(RevocationList list) {
        if (revocationFile == null) {
            return;
        }
        try {
            Files.createDirectories(revocationFile.getParent());
            Path tmp = revocationFile.resolveSibling(revocationFile.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            SecureFiles.createOwnerOnly(tmp);
            Files.write(tmp, list.encode());
            Files.move(tmp, revocationFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot persist revocations: " + e.getMessage(), e);
        }
    }

    private void loadRevocations() {
        if (revocationFile == null || !Files.isRegularFile(revocationFile)) {
            return;
        }
        try {
            RevocationList list = RevocationList.decode(Files.readAllBytes(revocationFile));
            int valid = Endorsement.countValid(authorities, RevocationList.DOMAIN, list.body(), list.endorsements());
            if (valid < threshold) {
                // someone edited the file: refusing to start is safer than silently forgetting revocations
                throw new IllegalStateException("revocation file " + revocationFile + " is not validly endorsed");
            }
            revocations = list;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + revocationFile, e);
        }
    }

    private static FsException denied(String why) {
        return new FsException(FsErrorCode.DENIED, why);
    }
}
