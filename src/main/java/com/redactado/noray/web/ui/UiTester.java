package com.redactado.noray.web.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives a page in memory, the way a browser with the runtime would, so module UIs can be tested without HTTP:
 *
 * <pre>{@code
 * UiTester ui = UiTester.open(Page.of("Notes", new NoteList(store)));
 * ui.change("title", "Groceries").click("Add");
 * assertTrue(ui.text().contains("Groceries"));
 * }</pre>
 *
 * It works on the renderer's own output (well-formed by construction, text always escaped), not on arbitrary HTML.
 */
public final class UiTester {

    private static final Pattern BOUND = Pattern.compile("<([a-zA-Z][a-zA-Z0-9-]*)((?:\\s[^>]*?)?)\\sdata-nr-on=\"([^\"]*)\"([^>]*)>");
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)=\"([^\"]*)\"");
    private static final Set<String> VOID = Set.of("input", "img", "br", "hr");

    private final PageView view;
    private final boolean operator;
    private final List<Blob> blobs = new ArrayList<>();
    private String html;
    private Instant clock = Instant.now();
    private PageView.Result last = new PageView.Result(List.of(), null, List.of());

    private UiTester(Page page, boolean operator, String appPath) {
        this.operator = operator;
        RequestContext rc = new RequestContext() {
            @Override public String param(String n) { return ""; }
            @Override public Optional<String> query(String n) { return Optional.empty(); }
            @Override public Map<String, String> params() { return Map.of(); }
            @Override public String path() { return appPath; }
            @Override public Optional<String> user() { return Optional.of(operator ? "operator:test" : "viewer:test"); }
            @Override public String link(String p) { return appPath + (p.equals("/") ? "" : p); }
        };
        this.view = new PageView(page, new PageView.Environment() {
            @Override public String link(String p) { return rc.link(p); }
            @Override public String blob(Blob b) {
                blobs.add(b);
                return "/_nr/blob/test-" + (blobs.size() - 1);
            }
            @Override public RequestContext request() { return rc; }
            @Override public String formEndpoint() { return "/_nr/form?t="; }
        });
        this.html = view.renderBody().html();
    }

    /** Opens a page as an operator. */
    public static UiTester open(Page page) {
        return new UiTester(page, true, "/apps/test");
    }

    /** Opens a page as an operator ({@code true}) or a read-only viewer. */
    public static UiTester open(Page page, boolean operator) {
        return new UiTester(page, operator, "/apps/test");
    }

    /** The current HTML of the page body, with every patch applied. */
    public String html() {
        return html;
    }

    /** The visible text (tags removed, entities decoded, whitespace collapsed). */
    public String text() {
        return textOf(html);
    }

    public boolean contains(String text) {
        return text().contains(text);
    }

    /** Clicks the first element with a click action whose text or {@code aria-label} contains {@code label}. */
    public UiTester click(String label) {
        return fire(find("click", label), "click", "", FormData.EMPTY);
    }

    /** Sets a value through the first change (or input) action on a field matched by name, id, aria-label or placeholder. */
    public UiTester change(String field, String value) {
        Bound b = findAny(field, "change", "input");
        return fire(b, b.tokens.containsKey("change") ? "change" : "input", value, FormData.EMPTY);
    }

    /** Submits the first form whose text contains {@code marker} with these fields. */
    public UiTester submit(String marker, Map<String, String> fields) {
        Map<String, List<String>> f = new LinkedHashMap<>();
        fields.forEach((k, v) -> f.put(k, List.of(v)));
        return fire(find("submit", marker), "submit", "", new FormData(f));
    }

    /** Submits the first upload form whose text contains {@code marker} with these files (and fields). */
    public UiTester upload(String marker, Map<String, String> fields, Upload... files) {
        Map<String, List<String>> f = new LinkedHashMap<>();
        fields.forEach((k, v) -> f.put(k, List.of(v)));
        return fire(find("submit", marker), "submit", "", new FormData(f, List.of(files)));
    }

    /** Moves time forward and applies the pushes of timed regions that became due. */
    public UiTester advance(Duration d) {
        clock = clock.plus(d);
        for (PageView.Patch p : view.due(clock)) apply(p);
        return this;
    }

    /** Notifications from the last action. */
    public List<Toast> toasts() {
        return last.toasts();
    }

    /** Where the last action navigated, or null. */
    public String navigatedTo() {
        return last.navigate();
    }

    /** Blobs the page asked URLs for, in order. */
    public List<Blob> blobs() {
        return List.copyOf(blobs);
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private record Bound(String tag, Map<String, String> attrs, Map<String, String> tokens, String text) {
    }

    private UiTester fire(Bound b, String event, String value, FormData form) {
        last = view.dispatch(b.tokens.get(event), value, form, operator);
        for (PageView.Patch p : last.patches()) apply(p);
        return this;
    }

    private Bound find(String event, String label) {
        for (Bound b : bound()) {
            if (b.tokens.containsKey(event) && (b.text.contains(label) || label.equals(b.attrs.get("aria-label")))) return b;
        }
        throw new AssertionError("no element with a " + event + " action matching '" + label + "' in: " + text());
    }

    private Bound findAny(String field, String... events) {
        for (Bound b : bound()) {
            boolean has = false;
            for (String e : events) has |= b.tokens.containsKey(e);
            if (has && (field.equals(b.attrs.get("name")) || field.equals(b.attrs.get("id")) || field.equals(b.attrs.get("aria-label"))
                    || field.equals(b.attrs.get("placeholder")))) {
                return b;
            }
        }
        throw new AssertionError("no field '" + field + "' with a change or input action");
    }

    private List<Bound> bound() {
        List<Bound> out = new ArrayList<>();
        Matcher m = BOUND.matcher(html);
        while (m.find()) {
            String tag = m.group(1);
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher a = ATTR.matcher(m.group(2) + " " + m.group(4));
            while (a.find()) attrs.put(a.group(1), unescape(a.group(2)));
            Map<String, String> tokens = new LinkedHashMap<>();
            for (String spec : m.group(3).split(" ")) {
                int c = spec.indexOf(':');
                if (c > 0) tokens.put(spec.substring(0, c), spec.substring(c + 1));
            }
            String inner = VOID.contains(tag) ? "" : html.substring(m.end(), closing(html, tag, m.end()));
            out.add(new Bound(tag, attrs, tokens, textOf(inner)));
        }
        return out;
    }

    private void apply(PageView.Patch p) {
        String open = "<div class=\"nr-region\" data-nr-region=\"" + p.region() + "\">";
        int start = html.indexOf(open);
        if (start < 0) return;
        int end = closing(html, "div", start + open.length()) + "</div>".length();
        html = html.substring(0, start) + p.html() + html.substring(end);
    }

    /** Index of the closing tag matching an element whose content starts at {@code from}. */
    private static int closing(String s, String tag, int from) {
        String openTag = "<" + tag;
        String closeTag = "</" + tag + ">";
        int depth = 1;
        int i = from;
        while (i < s.length()) {
            int o = s.indexOf(openTag, i);
            int c = s.indexOf(closeTag, i);
            if (c < 0) return s.length();
            if (o >= 0 && o < c && (s.charAt(o + openTag.length()) == ' ' || s.charAt(o + openTag.length()) == '>')) {
                depth++;
                i = o + openTag.length();
            } else if (o >= 0 && o < c) {
                i = o + openTag.length(); // a longer tag name with the same prefix (e.g. <details> vs <de>)
            } else {
                if (--depth == 0) return c;
                i = c + closeTag.length();
            }
        }
        return s.length();
    }

    static String textOf(String html) {
        return unescape(html.replaceAll("<[^>]*>", " ")).replaceAll("\\s+", " ").strip();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }
}
