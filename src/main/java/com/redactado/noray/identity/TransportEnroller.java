package com.redactado.noray.identity;

import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import com.redactado.noray.net.enroll.EnrollmentRecord;
import com.redactado.noray.net.enroll.EnrollmentStore;

import java.util.HexFormat;
import java.util.Set;

/** Admits peers to the mesh from their certificates: the certificate's keys become the transport enrollment. */
public final class TransportEnroller implements PeerEnroller {

    private final MeshTransport transport;
    private final EnrollmentStore store; // may be null: in-memory meshes have no key records

    public TransportEnroller(MeshTransport transport, EnrollmentStore store) {
        this.transport = transport;
        this.store = store;
    }

    @Override
    public void enroll(Certificate cert, String introHint) {
        NodeId peer = cert.nodeId();
        PeerCapabilities caps = new PeerCapabilities(true, false, Set.of());
        if (store != null) {
            HexFormat hex = HexFormat.of();
            // saved first: the persisting transport keeps the keys of an existing record when it enrolls
            store.save(new EnrollmentRecord(peer, caps.fsStore(), caps.repo(), caps.tags(), introHint,
                    hex.formatHex(cert.transportKey()), hex.formatHex(cert.kemKey())));
        }
        transport.enroll(peer, caps);
    }

    @Override
    public void unenroll(NodeId peer) {
        transport.revoke(peer);
    }
}
