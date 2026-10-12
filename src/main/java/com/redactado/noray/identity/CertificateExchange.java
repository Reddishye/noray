package com.redactado.noray.identity;

import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Peers introduce themselves by sending their certificate and learn about revocations by gossip. Everything that
 * arrives is verified against the {@link TrustRoot}; a peer can only vouch for itself, and a forged or replayed
 * document is simply ignored. A node that accepts a certificate for the first time answers with its own, so one
 * announcement is enough to make both sides know each other (the exchange stops when nothing is new).
 */
public final class CertificateExchange implements AutoCloseable {

    private static final byte CERT = 1;
    private static final byte REVOKE = 2;
    private static final int MAX_FRAME = 8 * 1024 * 1024;

    private final MeshTransport transport;
    private final PeerDirectory directory;
    private final TrustRoot trust;
    private final Supplier<Certificate> own;
    private final RevocationSink revocations;
    private final BiConsumer<NodeId, byte[]> handler = this::onFrame;
    private boolean started;

    /** Receives revocation lists that arrive from peers. */
    @FunctionalInterface
    public interface RevocationSink {
        /** @return true if the list was new and applied */
        boolean accept(RevocationList list) throws FsException;
    }

    public CertificateExchange(MeshTransport transport, PeerDirectory directory, TrustRoot trust,
                               Supplier<Certificate> own) {
        this(transport, directory, trust, own, trust::acceptRevocations);
    }

    public CertificateExchange(MeshTransport transport, PeerDirectory directory, TrustRoot trust,
                               Supplier<Certificate> own, RevocationSink revocations) {
        this.revocations = Objects.requireNonNull(revocations, "revocations");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.own = Objects.requireNonNull(own, "own");
    }

    public synchronized void start() {
        if (!started) {
            transport.subscribe(MeshChannel.IDENTITY, handler);
            started = true;
        }
    }

    @Override
    public synchronized void close() {
        if (started) {
            transport.unsubscribe(MeshChannel.IDENTITY, handler);
            started = false;
        }
    }

    /** Sends our certificate (and the revocation list we know) to one peer. */
    public void announce(NodeId peer) {
        Certificate cert = own.get();
        if (cert != null) {
            send(peer, CERT, cert.encode());
        }
        if (trust.revocations().version() > 0) {
            send(peer, REVOKE, trust.revocations().encode());
        }
    }

    public void announceAll() {
        for (NodeId peer : transport.peers()) {
            announce(peer);
        }
    }

    /** Sends the current revocation list to every peer except {@code except} (may be {@code null}). */
    public void gossipRevocations(NodeId except) {
        if (trust.revocations().version() == 0) {
            return;
        }
        byte[] bytes = trust.revocations().encode();
        for (NodeId peer : transport.peers()) {
            if (!peer.equals(except)) {
                send(peer, REVOKE, bytes);
            }
        }
    }

    private void send(NodeId peer, byte kind, byte[] body) {
        byte[] frame = new byte[body.length + 1];
        frame[0] = kind;
        System.arraycopy(body, 0, frame, 1, body.length);
        try {
            transport.send(peer, MeshChannel.IDENTITY, frame);
        } catch (FsException ignored) {
            // not reachable or not enrolled at the transport level: nothing to do
        }
    }

    private void onFrame(NodeId from, byte[] frame) {
        if (frame == null || frame.length < 2 || frame.length > MAX_FRAME) {
            return;
        }
        byte[] body = Arrays.copyOfRange(frame, 1, frame.length);
        try {
            switch (frame[0]) {
                case CERT -> {
                    if (directory.offer(from, Certificate.decode(body)) == PeerDirectory.Offer.ACCEPTED) {
                        announce(from); // introduce ourselves back; stops once nothing is new
                    }
                }
                case REVOKE -> {
                    if (revocations.accept(RevocationList.decode(body))) {
                        gossipRevocations(from);
                    }
                }
                default -> {
                }
            }
        } catch (IllegalArgumentException | FsException ignored) {
            // malformed or unauthorised document: drop it
        }
    }
}
