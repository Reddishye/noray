package com.redactado.noray.net.spi;

import java.util.Map;
import java.util.Objects;

public record PathHint(String scheme, String locator, Map<String, String> attrs) {
    public PathHint {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(locator, "locator");
        attrs = Map.copyOf(Objects.requireNonNull(attrs, "attrs"));
    }

    public static PathHint loopback() {
        return new PathHint("loopback", "local", Map.of());
    }
}