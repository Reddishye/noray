package com.redactado.noray.net.spi;

import com.redactado.noray.net.NodeId;
import java.util.List;

public interface PathPolicy {
    List<String> driverOrder(NodeId peer);
}