package com.redactado.noray.web.html;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The HTML DSL. Use with {@code import static com.redactado.noray.web.html.Html.*;}:
 *
 * <pre>{@code
 * div(cls("nr-card"), h2("Volumes"), p("Healthy: ", strong(n)), a(href("/apps/fs"), "Browse"))
 * }</pre>
 *
 * Builders take <em>parts</em>: {@link Node}s, {@link Attr}s, strings and numbers (escaped text), collections or arrays
 * of parts, and {@code null} (skipped, handy for conditionals). Anything else is a programming error and throws.
 */
public final class Html {

    private Html() {
    }

    // ── core ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Any (validated) element. */
    public static Node.Element el(String tag, Object... parts) {
        List<Attr> attrs = new ArrayList<>();
        List<Node> children = new ArrayList<>();
        collect(parts, attrs, children);
        return new Node.Element(tag, mergeClasses(attrs), children);
    }

    public static Node text(Object value) {
        return new Node.Text(value == null ? "" : String.valueOf(value));
    }

    public static Node fragment(Object... parts) {
        List<Attr> attrs = new ArrayList<>();
        List<Node> children = new ArrayList<>();
        collect(parts, attrs, children);
        if (!attrs.isEmpty()) throw new IllegalArgumentException("a fragment cannot carry attributes");
        return new Node.Fragment(children);
    }

    public static final Node EMPTY = new Node.Fragment(List.of());

    /** {@code node} when the condition holds, else nothing. */
    public static Object when(boolean condition, Object part) {
        return condition ? part : null;
    }

    public static String render(Node node) {
        return node.html();
    }

    private static void collect(Object part, List<Attr> attrs, List<Node> children) {
        switch (part) {
            case null -> { }
            case Node n -> children.add(n);
            case Attr a -> attrs.add(a);
            case Attr[] as -> { for (Attr a : as) collect(a, attrs, children); }
            case CharSequence s -> children.add(new Node.Text(s.toString()));
            case Number n -> children.add(new Node.Text(n.toString()));
            case Boolean b -> children.add(new Node.Text(b.toString()));
            case Character c -> children.add(new Node.Text(c.toString()));
            case Object[] arr -> { for (Object o : arr) collect(o, attrs, children); }
            case Iterable<?> it -> { for (Object o : it) collect(o, attrs, children); }
            case Optional<?> o -> collect(o.orElse(null), attrs, children);
            default -> throw new IllegalArgumentException("not an HTML part: " + part.getClass().getName()
                    + " (use text(...) to show an object)");
        }
    }

    /** Attributes that may be given several times and are merged with a space ({@code class}, bound actions). */
    private static final Set<String> MERGED = Set.of("class", "data-nr-on");

    /** Several {@code class} (or action) attributes on one element are merged, so components can add to a caller's. */
    private static List<Attr> mergeClasses(List<Attr> attrs) {
        Map<String, StringJoiner> merged = new LinkedHashMap<>();
        List<Attr> out = new ArrayList<>();
        for (Attr a : attrs) {
            if (!MERGED.contains(a.name())) {
                out.add(a);
                continue;
            }
            StringJoiner j = merged.get(a.name());
            if (j == null) {
                j = new StringJoiner(" ");
                merged.put(a.name(), j);
                out.add(a); // placeholder keeps the first position
            }
            if (a.value() != null && !a.value().isBlank()) j.add(a.value());
        }
        if (merged.isEmpty()) return attrs;
        for (int i = 0; i < out.size(); i++) {
            StringJoiner j = merged.get(out.get(i).name());
            if (j != null) out.set(i, new Attr(out.get(i).name(), j.toString()));
        }
        return out;
    }

    // ── attributes ────────────────────────────────────────────────────────────────────────────────────────────────────

    public static Attr attr(String name, Object value) { return new Attr(name, value == null ? null : String.valueOf(value)); }
    public static Attr flag(String name) { return new Attr(name, null); }
    /** Classes, skipping nulls and blanks: {@code cls("btn", primary ? "btn-primary" : null)}. */
    public static Attr cls(String... names) {
        StringJoiner j = new StringJoiner(" ");
        for (String s : names) if (s != null && !s.isBlank()) j.add(s);
        return new Attr("class", j.toString());
    }
    public static Attr id(String v) { return new Attr("id", v); }
    public static Attr href(String v) { return new Attr("href", v); }
    public static Attr src(String v) { return new Attr("src", v); }
    public static Attr alt(String v) { return new Attr("alt", v); }
    public static Attr title(String v) { return new Attr("title", v); }
    public static Attr type(String v) { return new Attr("type", v); }
    public static Attr name(String v) { return new Attr("name", v); }
    public static Attr value(Object v) { return new Attr("value", String.valueOf(v)); }
    public static Attr placeholder(String v) { return new Attr("placeholder", v); }
    public static Attr forId(String v) { return new Attr("for", v); }
    public static Attr role(String v) { return new Attr("role", v); }
    public static Attr aria(String name, Object v) { return new Attr("aria-" + name, String.valueOf(v)); }
    public static Attr data(String name, Object v) { return new Attr("data-" + name, String.valueOf(v)); }
    public static Attr target(String v) { return new Attr("target", v); }
    public static Attr rel(String v) { return new Attr("rel", v); }
    public static Attr method(String v) { return new Attr("method", v); }
    public static Attr action(String v) { return new Attr("action", v); }
    public static Attr colspan(int n) { return new Attr("colspan", String.valueOf(n)); }
    public static Attr scope(String v) { return new Attr("scope", v); }
    public static Attr tabindex(int n) { return new Attr("tabindex", String.valueOf(n)); }
    public static Attr disabled() { return flag("disabled"); }
    public static Attr checked() { return flag("checked"); }
    public static Attr selected() { return flag("selected"); }
    public static Attr required() { return flag("required"); }
    public static Attr readonly() { return flag("readonly"); }
    public static Attr hidden() { return flag("hidden"); }
    public static Attr open() { return flag("open"); }
    public static Attr multiple() { return flag("multiple"); }
    public static Attr download(String filename) { return new Attr("download", filename); }

    // ── elements ──────────────────────────────────────────────────────────────────────────────────────────────────────

    public static Node.Element div(Object... p) { return el("div", p); }
    public static Node.Element span(Object... p) { return el("span", p); }
    public static Node.Element p(Object... p) { return el("p", p); }
    public static Node.Element a(Object... p) { return el("a", p); }
    public static Node.Element h1(Object... p) { return el("h1", p); }
    public static Node.Element h2(Object... p) { return el("h2", p); }
    public static Node.Element h3(Object... p) { return el("h3", p); }
    public static Node.Element h4(Object... p) { return el("h4", p); }
    public static Node.Element strong(Object... p) { return el("strong", p); }
    public static Node.Element em(Object... p) { return el("em", p); }
    public static Node.Element small(Object... p) { return el("small", p); }
    public static Node.Element code(Object... p) { return el("code", p); }
    public static Node.Element pre(Object... p) { return el("pre", p); }
    public static Node.Element kbd(Object... p) { return el("kbd", p); }
    public static Node.Element blockquote(Object... p) { return el("blockquote", p); }
    public static Node.Element ul(Object... p) { return el("ul", p); }
    public static Node.Element ol(Object... p) { return el("ol", p); }
    public static Node.Element li(Object... p) { return el("li", p); }
    public static Node.Element dl(Object... p) { return el("dl", p); }
    public static Node.Element dt(Object... p) { return el("dt", p); }
    public static Node.Element dd(Object... p) { return el("dd", p); }
    public static Node.Element table(Object... p) { return el("table", p); }
    public static Node.Element caption(Object... p) { return el("caption", p); }
    public static Node.Element thead(Object... p) { return el("thead", p); }
    public static Node.Element tbody(Object... p) { return el("tbody", p); }
    public static Node.Element tr(Object... p) { return el("tr", p); }
    public static Node.Element th(Object... p) { return el("th", p); }
    public static Node.Element td(Object... p) { return el("td", p); }
    public static Node.Element form(Object... p) { return el("form", p); }
    public static Node.Element input(Object... p) { return el("input", p); }
    public static Node.Element textarea(Object... p) { return el("textarea", p); }
    public static Node.Element select(Object... p) { return el("select", p); }
    public static Node.Element option(Object... p) { return el("option", p); }
    public static Node.Element label(Object... p) { return el("label", p); }
    public static Node.Element button(Object... p) { return el("button", p); }
    public static Node.Element fieldset(Object... p) { return el("fieldset", p); }
    public static Node.Element legend(Object... p) { return el("legend", p); }
    public static Node.Element nav(Object... p) { return el("nav", p); }
    public static Node.Element header(Object... p) { return el("header", p); }
    public static Node.Element footer(Object... p) { return el("footer", p); }
    public static Node.Element main(Object... p) { return el("main", p); }
    public static Node.Element section(Object... p) { return el("section", p); }
    public static Node.Element article(Object... p) { return el("article", p); }
    public static Node.Element aside(Object... p) { return el("aside", p); }
    public static Node.Element details(Object... p) { return el("details", p); }
    public static Node.Element summary(Object... p) { return el("summary", p); }
    public static Node.Element dialog(Object... p) { return el("dialog", p); }
    public static Node.Element figure(Object... p) { return el("figure", p); }
    public static Node.Element figcaption(Object... p) { return el("figcaption", p); }
    public static Node.Element img(Object... p) { return el("img", p); }
    public static Node.Element audio(Object... p) { return el("audio", p); }
    public static Node.Element video(Object... p) { return el("video", p); }
    public static Node.Element time(Object... p) { return el("time", p); }
    public static Node.Element progress(Object... p) { return el("progress", p); }
    public static Node.Element meter(Object... p) { return el("meter", p); }
    public static Node.Element br() { return el("br"); }
    public static Node.Element hr() { return el("hr"); }

    // ── SVG (charts) ──────────────────────────────────────────────────────────────────────────────────────────────────

    public static Node.Element svg(Object... p) { return el("svg", p); }
    public static Node.Element g(Object... p) { return el("g", p); }
    public static Node.Element path(Object... p) { return el("path", p); }
    public static Node.Element line(Object... p) { return el("line", p); }
    public static Node.Element rect(Object... p) { return el("rect", p); }
    public static Node.Element circle(Object... p) { return el("circle", p); }
    public static Node.Element polyline(Object... p) { return el("polyline", p); }
    public static Node.Element svgText(Object... p) { return el("text", p); }
    public static Node.Element svgTitle(Object... p) { return el("title", p); }
    public static Node.Element desc(Object... p) { return el("desc", p); }
}
