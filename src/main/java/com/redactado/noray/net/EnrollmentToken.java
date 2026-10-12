package com.redactado.noray.net;

import java.util.Objects;

/** Opaque enrollment token. Unused until real net; type kept for SPI. */
public record EnrollmentToken(String opaque) {
    public EnrollmentToken {
        Objects.requireNonNull(opaque, "opaque");
    }
}
