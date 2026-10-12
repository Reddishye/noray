package com.redactado.noray.identity;

import com.redactado.noray.net.NodeId;

/** The transport side of admitting and removing a peer; implemented by the wiring so that identity does not depend on the overlay. */
public interface PeerEnroller {

    /** Makes the peer reachable: transport enrollment with the keys the certificate names. */
    void enroll(Certificate cert, String introHint);

    /** Closes the door on the peer (revocation). */
    void unenroll(NodeId peer);

    PeerEnroller NONE = new PeerEnroller() {
        @Override public void enroll(Certificate cert, String introHint) { }
        @Override public void unenroll(NodeId peer) { }
    };
}
