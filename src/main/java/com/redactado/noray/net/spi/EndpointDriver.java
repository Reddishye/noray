package com.redactado.noray.net.spi;

import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.NodeId;

public interface EndpointDriver {
    String name();
    boolean supports(PathHint hint);
    EstablishedPath open(NodeId peer, PathHint hint) throws FsException;
}