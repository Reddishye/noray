package com.redactado.noray.web.server;

import java.util.Locale;
import java.util.Map;

/** Content types for public site files, by extension. Unknown extensions are served as bytes to download. */
final class SiteTypes {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("md", "text/plain; charset=utf-8"),
            Map.entry("xml", "application/xml"), Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"), Map.entry("avif", "image/avif"), Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"), Map.entry("woff", "font/woff"), Map.entry("pdf", "application/pdf"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("mp3", "audio/mpeg"),
            Map.entry("ogg", "audio/ogg"), Map.entry("wasm", "application/wasm"), Map.entry("csv", "text/csv; charset=utf-8"));

    private SiteTypes() {
    }

    static String of(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
