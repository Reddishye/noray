package com.redactado.noray.web.ui;

import java.util.function.Supplier;

/**
 * Bytes to serve under a short-lived URL (images in a preview, a download). The server decides the final content type
 * from {@code mediaType} and a sniff of the bytes; only images, audio and video are served inline, everything else as
 * an attachment, always with {@code nosniff} and a sandboxing CSP.
 */
public record Blob(String filename, String mediaType, Supplier<byte[]> bytes) {
}
