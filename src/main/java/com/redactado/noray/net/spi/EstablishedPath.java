package com.redactado.noray.net.spi;

import com.redactado.noray.net.NodeId;

public interface EstablishedPath {
    NodeId peer();
    void write(byte[] frame);
    void close();
}