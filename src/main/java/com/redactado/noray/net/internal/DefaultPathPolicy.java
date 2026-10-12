package com.redactado.noray.net.internal;

import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.spi.PathPolicy;

import java.util.List;

public final class DefaultPathPolicy implements PathPolicy {

    @Override
    public List<String> driverOrder(NodeId peer) {
        return List.of("loopback", "intro", "noise-udp");
    }
}
