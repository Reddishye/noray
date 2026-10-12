package com.redactado.noray.net;

import java.util.Objects;
import java.util.Set;

public record PeerCapabilities(boolean fsStore, boolean repo, Set<String> tags) {
    public PeerCapabilities {
        Objects.requireNonNull(tags, "tags");
        tags = Set.copyOf(tags);
    }
}
