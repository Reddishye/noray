package com.redactado.noray.web.kit;

import com.redactado.noray.web.html.Node;
import com.redactado.noray.web.ui.Blob;
import com.redactado.noray.web.ui.Json;
import com.redactado.noray.web.ui.MediaTypes;
import com.redactado.noray.web.ui.Render;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static com.redactado.noray.web.html.Html.*;
import static com.redactado.noray.web.kit.Kit.*;

/**
 * Shows what a file contains without ever executing it. Previewers are tried in order; modules can add their own with
 * {@link #register}. Content is read through a supplier the caller controls (normally the module's own scoped
 * filesystem), so a preview can show only what the module could read anyway.
 */
public final class FilePreview {

    /** A file to preview. {@code content} is called at most once per render. */
    public record FileInfo(String name, String path, Supplier<byte[]> content) {
    }

    /** Turns file bytes into a view. */
    public interface Previewer {
        boolean accepts(FileInfo file, MediaTypes.Type type);

        Node render(Render r, FileInfo file, MediaTypes.Type type, byte[] data);
    }

    /** Larger files are not previewed inline (only their metadata and a download link). */
    public static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;

    private static final List<Previewer> PREVIEWERS = new CopyOnWriteArrayList<>(List.of(
            new ImagePreviewer(), new MediaPreviewer(), new PdfPreviewer(), new MarkdownPreviewer(), new JsonPreviewer(),
            new CsvPreviewer(), new TextPreviewer(), new HexPreviewer()));

    private FilePreview() {
    }

    /** Adds a previewer ahead of the built-in ones. */
    public static void register(Previewer p) {
        PREVIEWERS.addFirst(p);
    }

    public static Node render(Render r, FileInfo file) {
        byte[] data;
        try {
            data = file.content().get();
        } catch (RuntimeException e) {
            return alert(Tone.ERROR, "Cannot read " + file.name(), e.getMessage());
        }
        MediaTypes.Type type = MediaTypes.detect(file.name(), Arrays.copyOf(data, Math.min(data.length, 4096)));
        Node download = a(cls("nr-btn", "nr-btn-s"), href(r.blob(new Blob(file.name(), "application/octet-stream", () -> data))),
                download(file.name()), "Download");
        Node meta = row(badge(type.kind().name().toLowerCase(), Tone.NEUTRAL), span(cls("nr-muted", "nr-num"), bytes(data.length)),
                span(cls("nr-muted"), type.mediaType()), download);
        if (data.length > MAX_PREVIEW_BYTES && type.kind() != MediaTypes.Kind.IMAGE
                && type.kind() != MediaTypes.Kind.AUDIO && type.kind() != MediaTypes.Kind.VIDEO) {
            return stack(meta, empty("Too large to preview (" + bytes(data.length) + ")."));
        }
        for (Previewer p : PREVIEWERS) {
            if (p.accepts(file, type)) {
                Node body;
                try {
                    body = p.render(r, file, type, data);
                } catch (RuntimeException e) {
                    body = alert(Tone.WARN, "Preview failed", e.getMessage());
                }
                return stack(meta, body);
            }
        }
        return stack(meta, empty("No preview for this kind of file."));
    }

    static String utf8(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    // ── built-in previewers ───────────────────────────────────────────────────────────────────────────────────────────

    static final class ImagePreviewer implements Previewer {
        public boolean accepts(FileInfo f, MediaTypes.Type t) { return t.kind() == MediaTypes.Kind.IMAGE; }
        public Node render(Render r, FileInfo f, MediaTypes.Type t, byte[] data) {
            return figure(cls("nr-media", "nr-media-contain"), img(alt(f.name()), src(r.blob(new Blob(f.name(), t.mediaType(), () -> data)))),
                    figcaption(f.name()));
        }
    }

    static final class MediaPreviewer implements Previewer {
        public boolean accepts(FileInfo f, MediaTypes.Type t) {
            return t.kind() == MediaTypes.Kind.AUDIO || t.kind() == MediaTypes.Kind.VIDEO;
        }
        public Node render(Render r, FileInfo f, MediaTypes.Type t, byte[] data) {
            String url = r.blob(new Blob(f.name(), t.mediaType(), () -> data));
            return t.kind() == MediaTypes.Kind.AUDIO
                    ? audio(cls("nr-preview-media"), flag("controls"), attr("preload", "metadata"), src(url), aria("label", f.name()))
                    : video(cls("nr-preview-media"), flag("controls"), attr("preload", "metadata"), src(url), aria("label", f.name()));
        }
    }

    static final class PdfPreviewer implements Previewer {
        public boolean accepts(FileInfo f, MediaTypes.Type t) { return t.kind() == MediaTypes.Kind.PDF; }
        public Node render(Render r, FileInfo f, MediaTypes.Type t, byte[] data) {
            int pages = 0;
            String s = new String(data, StandardCharsets.ISO_8859_1);
            for (int i = s.indexOf("/Type /Page"); i >= 0; i = s.indexOf("/Type /Page", i + 1)) {
                if (!s.startsWith("/Type /Pages", i)) pages++;
            }
            return alert(Tone.INFO, "PDF document", "PDFs are not rendered inside the dashboard (they can carry scripts). "
                    + (pages > 0 ? "About " + pages + " page(s). " : "") + "Download it to open it in a viewer you trust.");
        }
    }

    static final class MarkdownPreviewer implements Previewer {
        public boolean accepts(FileInfo f, MediaTypes.Type t) { return t.kind() == MediaTypes.Kind.TEXT && "md".equals(t.language()); }
        public Node render(Render r, FileInfo f, MediaTypes.Type t, byte[] data) {
            return stack(Markdown.render(utf8(data)), details(summary("Show source"), CodeView.of(utf8(data), "md")));
        }
    }

    static final class JsonPreviewer implements Previewer {
        public boolean accepts(FileInfo f, MediaTypes.Type t) { return t.kind() == MediaTypes.Kind.TEXT && "json".equals(t.language()); }
        public Node render(Render r, FileInfo f, MediaTypes.Type t, byte[] data) {
            String text = utf8(data);
            try {
                StringBuilder sb = new StringBuilder();
                pretty(Json.parse(text), 0, sb);
                return CodeView.of(sb.toString(), "json");
            } catch (IllegalArgumentException e) {
                return stack(alert(Tone.WARN, "Not valid JSON", e.getMessage()), CodeView.of(text, "json"));
            }
        }

        private static void pretty(Object v, int depth, StringBuilder sb) {
            String pad = "  ".repeat(depth + 1), end = "  ".repeat(depth);
            if (v instanceof Map<?, ?> m) {
                if (m.isEmpty()) { sb.append("{}"); return; }
                sb.append("{\n");
                int i = 0;
                for (var e : m.entrySet()) {
                    sb.append(pad).append(Json.quote(String.valueOf(e.getKey()))).append(": ");
                    pretty(e.getValue(), depth + 1, sb);
                    sb.append(++i < m.size() ? ",\n" : "\n");
                }
                sb.append(end).append('}');
            } else if (v instanceof List<?> l) {
                if (l.isEmpty()) { sb.append("[]"); return; }
                sb.append("[\n");
                for (int i = 0; i < l.size(); i++) {
                    sb.append(pad);
                    pretty(l.get(i), depth + 1, sb);
                    sb.append(i + 1 < l.size() ? ",\n" : "\n");
                }
                sb.append(end).append(']');
            } else if (v instanceof String s) {
                sb.append(Json.quote(s));
            } else if (v instanceof Double d) {
                sb.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : d.toString());
            } else {
                sb.append(v);
            }
        }
    }

    static final class CsvPreviewer implements Previewer {
        private static final int MAX_ROWS = 500;
        public boolean accepts(FileInfo f, MediaTypes.Type t) { return t.kind() == MediaTypes.Kind.TEXT && "csv".equals(t.language()); }
        public Node render(Render r, FileInfo f, MediaTypes.Type t, byte[] data) {
            char sep = f.name().toLowerCase().endsWith(".tsv") ? '\t' : ',';
            String[] lines = utf8(data).replace("\r", "").split("\n");
            List<Node> rows = new ArrayList<>();
            Node head = null;
            for (int i = 0; i < Math.min(lines.length, MAX_ROWS + 1); i++) {
                if (lines[i].isEmpty()) continue;
                List<Node> cells = new ArrayList<>();
                for (String c : split(lines[i], sep)) cells.add(i == 0 ? th(scope("col"), c) : td(c));
                if (i == 0) head = thead(tr(cells)); else rows.add(tr(cells));
            }
            return div(div(cls("nr-table-wrap"), table(cls("nr-table"), caption(cls("nr-visually-hidden"), f.name()), head, tbody(rows))),
                    lines.length > MAX_ROWS + 1 ? p(cls("nr-muted"), "First " + MAX_ROWS + " of " + (lines.length - 1) + " rows.") : null);
        }

        static List<String> split(String line, char sep) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else if (c == '"') quoted = false;
                    else cur.append(c);
                } else if (c == '"') quoted = true;
                else if (c == sep) { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
            out.add(cur.toString());
            return out;
        }
    }

    static final class TextPreviewer implements Previewer {
        public boolean accepts(FileInfo f, MediaTypes.Type t) { return t.kind() == MediaTypes.Kind.TEXT; }
        public Node render(Render r, FileInfo f, MediaTypes.Type t, byte[] data) {
            return CodeView.of(utf8(data), t.language());
        }
    }

    static final class HexPreviewer implements Previewer {
        private static final int MAX = 4096;
        public boolean accepts(FileInfo f, MediaTypes.Type t) { return true; }
        public Node render(Render r, FileInfo f, MediaTypes.Type t, byte[] data) {
            StringBuilder sb = new StringBuilder();
            int n = Math.min(data.length, MAX);
            for (int off = 0; off < n; off += 16) {
                sb.append(String.format("%08x  ", off));
                StringBuilder ascii = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    if (off + i < n) {
                        int b = data[off + i] & 0xFF;
                        sb.append(String.format("%02x ", b));
                        ascii.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
                    } else {
                        sb.append("   ");
                    }
                    if (i == 7) sb.append(' ');
                }
                sb.append(' ').append(ascii).append('\n');
            }
            return div(pre(cls("nr-log"), sb.toString()),
                    data.length > MAX ? p(cls("nr-muted"), "First " + bytes(MAX) + " of " + bytes(data.length) + ".") : null);
        }
    }
}
