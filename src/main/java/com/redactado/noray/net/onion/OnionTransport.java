package com.redactado.noray.net.onion;

import com.redactado.noray.net.NodeId;

import java.util.function.BiConsumer;

/** Moves fixed-size cells between neighbouring nodes. The onion layer does not care how. */
public interface OnionTransport {

    /** Hands a cell to a neighbour; throws if it cannot be sent. */
    void send(NodeId neighbour, byte[] cell);

    /** Receives cells from neighbours: (who handed it over, the cell). */
    void onCell(BiConsumer<NodeId, byte[]> handler);
}
