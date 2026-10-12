package com.redactado.noray.web.kit;

import com.redactado.noray.web.html.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.redactado.noray.web.html.Html.*;

/**
 * A safe Markdown subset rendered to nodes (never to HTML text): headings, paragraphs, emphasis, inline code, fenced code,
 * lists, block quotes, rules, links and tables. Raw HTML in the source is shown as text. Links with an unsafe scheme
 * are rendered as plain text, and every link to elsewhere gets {@code rel="noopener noreferrer"}.
 */
public final class Markdown {

    private static final Pattern INLINE = Pattern.compile(
            "`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|\\*(.+?)\\*|_(.+?)_|\\[([^\\]]+)]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("(#{1,6})\\s+(.*)");
    private static final Pattern ORDERED = Pattern.compile("\\d+[.)]\\s+(.*)");
    private static final int MAX_DEPTH = 8;

    private Markdown() {
    }

    public static Node render(String source) {
        String[] lines = source.replace("\r", "").split("\n", -1);
        List<Node> out = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String t = line.strip();
            if (t.isEmpty()) { i++; continue; }
            if (t.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                String lang = t.substring(3).strip();
                i++;
                while (i < lines.length && !lines[i].strip().startsWith("```")) code.append(lines[i++]).append('\n');
                i++;
                out.add(CodeView.of(code.toString().stripTrailing(), lang));
                continue;
            }
            Matcher h = HEADING.matcher(t);
            if (h.matches()) {
                int level = Math.min(4, h.group(1).length() + 1); // the page owns <h1>
                out.add(el("h" + level, inline(h.group(2), 0)));
                i++;
                continue;
            }
            if (t.matches("(-{3,}|\\*{3,}|_{3,})")) { out.add(hr()); i++; continue; }
            if (t.startsWith(">")) {
                StringBuilder q = new StringBuilder();
                while (i < lines.length && lines[i].strip().startsWith(">")) q.append(lines[i++].strip().substring(1).strip()).append('\n');
                out.add(blockquote(render(q.toString())));
                continue;
            }
            if (t.startsWith("|") && i + 1 < lines.length && lines[i + 1].strip().matches("\\|?\\s*:?-{3,}.*")) {
                List<String> header = cells(t);
                i += 2;
                List<Node> rows = new ArrayList<>();
                while (i < lines.length && lines[i].strip().startsWith("|")) {
                    List<Node> tds = new ArrayList<>();
                    for (String c : cells(lines[i++].strip())) tds.add(td(inline(c, 0)));
                    rows.add(tr(tds));
                }
                List<Node> ths = new ArrayList<>();
                for (String c : header) ths.add(th(scope("col"), inline(c, 0)));
                out.add(div(cls("nr-table-wrap"), table(thead(tr(ths)), tbody(rows))));
                continue;
            }
            if (t.matches("[-*+]\\s+.*") || ORDERED.matcher(t).matches()) {
                boolean ordered = ORDERED.matcher(t).matches();
                List<Node> items = new ArrayList<>();
                while (i < lines.length) {
                    String s = lines[i].strip();
                    Matcher om = ORDERED.matcher(s);
                    if (!ordered && s.matches("[-*+]\\s+.*")) items.add(li(inline(s.substring(1).strip(), 0)));
                    else if (ordered && om.matches()) items.add(li(inline(om.group(1), 0)));
                    else break;
                    i++;
                }
                out.add(ordered ? ol(items) : ul(items));
                continue;
            }
            StringBuilder para = new StringBuilder(t);
            i++;
            while (i < lines.length && !lines[i].strip().isEmpty() && !startsBlock(lines[i].strip())) {
                para.append(' ').append(lines[i++].strip());
            }
            out.add(p(inline(para.toString(), 0)));
        }
        return div(cls("nr-md"), out);
    }

    private static boolean startsBlock(String t) {
        return t.startsWith("#") || t.startsWith("```") || t.startsWith(">") || t.startsWith("|")
                || t.matches("[-*+]\\s+.*") || ORDERED.matcher(t).matches() || t.matches("(-{3,}|\\*{3,}|_{3,})");
    }

    private static List<String> cells(String row) {
        String r = row.strip();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        List<String> out = new ArrayList<>();
        for (String c : r.split("\\|", -1)) out.add(c.strip());
        return out;
    }

    static List<Node> inline(String s, int depth) {
        List<Node> out = new ArrayList<>();
        if (depth > MAX_DEPTH) {
            out.add(text(s));
            return out;
        }
        Matcher m = INLINE.matcher(s);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(text(s.substring(last, m.start())));
            if (m.group(1) != null) out.add(code(m.group(1)));
            else if (m.group(2) != null) out.add(strong(inline(m.group(2), depth + 1)));
            else if (m.group(3) != null) out.add(strong(inline(m.group(3), depth + 1)));
            else if (m.group(4) != null) out.add(em(inline(m.group(4), depth + 1)));
            else if (m.group(5) != null) out.add(em(inline(m.group(5), depth + 1)));
            else out.add(link(m.group(6), m.group(7), depth));
            last = m.end();
        }
        if (last < s.length()) out.add(text(s.substring(last)));
        return out;
    }

    private static Node link(String label, String url, int depth) {
        try {
            boolean external = url.matches("(?i)https?:.*");
            return a(href(url), external ? rel("noopener noreferrer") : null, external ? target("_blank") : null,
                    inline(label, depth + 1));
        } catch (IllegalArgumentException unsafe) {
            return span(label + " (" + url + ")"); // javascript: and friends become harmless text
        }
    }
}
