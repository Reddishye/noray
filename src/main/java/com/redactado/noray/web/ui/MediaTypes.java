package com.redactado.noray.web.ui;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * What a file is, decided from its first bytes first and its name second. The result drives both how it is previewed and
 * how it may be served: only the {@link Kind#IMAGE}, {@link Kind#AUDIO} and {@link Kind#VIDEO} types are ever served
 * inline; HTML, SVG documents, scripts and everything else are text to show or bytes to download.
 */
public final class MediaTypes {

    public enum Kind { IMAGE, AUDIO, VIDEO, PDF, TEXT, BINARY }

    public record Type(Kind kind, String mediaType, String language) {
    }

    private static final Map<String, String> TEXT_EXT = Map.ofEntries(
            Map.entry("txt", "text"), Map.entry("md", "md"), Map.entry("markdown", "md"), Map.entry("json", "json"),
            Map.entry("csv", "csv"), Map.entry("tsv", "csv"), Map.entry("log", "log"), Map.entry("java", "java"),
            Map.entry("kt", "kotlin"), Map.entry("js", "js"), Map.entry("mjs", "js"), Map.entry("ts", "ts"),
            Map.entry("py", "py"), Map.entry("rs", "rust"), Map.entry("go", "go"), Map.entry("c", "c"), Map.entry("h", "c"),
            Map.entry("cpp", "cpp"), Map.entry("sh", "sh"), Map.entry("xml", "xml"), Map.entry("html", "html"),
            Map.entry("htm", "html"), Map.entry("css", "css"), Map.entry("svg", "xml"), Map.entry("yaml", "yaml"),
            Map.entry("yml", "yaml"), Map.entry("toml", "toml"), Map.entry("ini", "ini"), Map.entry("conf", "conf"),
            Map.entry("properties", "properties"), Map.entry("env", "env"), Map.entry("sql", "sql"),
            Map.entry("gradle", "groovy"), Map.entry("kts", "kotlin"), Map.entry("service", "ini"));

    private MediaTypes() {
    }

    public static Type detect(String filename, byte[] head) {
        String ext = extension(filename);
        if (startsWith(head, 0x89, 'P', 'N', 'G')) return new Type(Kind.IMAGE, "image/png", null);
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) return new Type(Kind.IMAGE, "image/jpeg", null);
        if (startsWith(head, 'G', 'I', 'F', '8')) return new Type(Kind.IMAGE, "image/gif", null);
        if (startsWith(head, 'R', 'I', 'F', 'F') && head.length > 11 && head[8] == 'W' && head[9] == 'E' && head[10] == 'B'
                && head[11] == 'P') return new Type(Kind.IMAGE, "image/webp", null);
        if (head.length > 11 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            String brand = new String(head, 8, 4, StandardCharsets.US_ASCII);
            if (brand.startsWith("avif") || brand.startsWith("avis")) return new Type(Kind.IMAGE, "image/avif", null);
            if (brand.startsWith("M4A")) return new Type(Kind.AUDIO, "audio/mp4", null);
            return new Type(Kind.VIDEO, "video/mp4", null);
        }
        if (startsWith(head, 0x1A, 0x45, 0xDF, 0xA3)) return new Type(Kind.VIDEO, "video/webm", null);
        if (startsWith(head, 'O', 'g', 'g', 'S')) return new Type(Kind.AUDIO, "audio/ogg", null);
        if (startsWith(head, 'I', 'D', '3') || (head.length > 1 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0 && "mp3".equals(ext))) {
            return new Type(Kind.AUDIO, "audio/mpeg", null);
        }
        if (startsWith(head, 'f', 'L', 'a', 'C')) return new Type(Kind.AUDIO, "audio/flac", null);
        if (startsWith(head, 'R', 'I', 'F', 'F') && head.length > 11 && head[8] == 'W' && head[9] == 'A' && head[10] == 'V') {
            return new Type(Kind.AUDIO, "audio/wav", null);
        }
        if (startsWith(head, '%', 'P', 'D', 'F')) return new Type(Kind.PDF, "application/pdf", null);
        if (isText(head)) {
            // SVG is text here on purpose: shown as source, served only as an image inside <img> (see the server)
            return new Type(Kind.TEXT, "text/plain; charset=utf-8", TEXT_EXT.getOrDefault(ext, ext.isEmpty() ? "text" : ext));
        }
        return new Type(Kind.BINARY, "application/octet-stream", null);
    }

    /** UTF-8 without NUL bytes (a truncated multi-byte sequence at the very end is allowed). */
    static boolean isText(byte[] head) {
        for (byte b : head) if (b == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(head, 0, trimPartial(head)));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static int trimPartial(byte[] b) {
        int end = b.length;
        for (int i = 1; i <= 3 && end - i >= 0; i++) {
            int c = b[end - i] & 0xFF;
            if ((c & 0xC0) == 0xC0) return end - i; // a lead byte whose continuation was cut off
            if ((c & 0xC0) != 0x80) break;
        }
        return end;
    }

    public static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] b, int... sig) {
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[i] & 0xFF) != sig[i]) return false;
        return true;
    }
}
