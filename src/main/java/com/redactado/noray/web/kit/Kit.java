package com.redactado.noray.web.kit;

import com.redactado.noray.web.html.Attr;
import com.redactado.noray.web.html.Node;
import com.redactado.noray.web.ui.Render;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import static com.redactado.noray.web.html.Html.*;

/**
 * Stateless building blocks. Each returns a {@link Node}; stateful components ({@link Table}, {@link Tabs},
 * {@link FileBrowser}) are classes. Labels are parameters, never optional, so every control is announced properly.
 */
public final class Kit {

    private Kit() {
    }

    /** Semantic colour of a badge, alert or button. Colour is never the only signal: the text says it too. */
    public enum Tone { NEUTRAL, OK, WARN, ERROR, INFO }

    // ── layout ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The heading of a page, an optional line of context, and actions on the right. */
    public static Node pageHeader(String title, String subtitle, Object... actions) {
        return div(cls("nr-page-h"),
                div(h1(title), subtitle == null || subtitle.isEmpty() ? null : p(cls("nr-muted"), subtitle)),
                actions.length == 0 ? null : div(cls("nr-row"), actions));
    }

    public static Node card(String title, Object... body) {
        return cardWithActions(title, List.of(), body);
    }

    public static Node cardWithActions(String title, List<?> actions, Object... body) {
        return section(cls("nr-card"), aria("label", title),
                div(cls("nr-card-h"), h2(title), actions.isEmpty() ? null : div(cls("nr-row"), actions)),
                div(cls("nr-card-b"), body));
    }

    /** A card whose body touches the edges (tables, code). */
    public static Node flushCard(String title, List<?> actions, Object... body) {
        return section(cls("nr-card"), aria("label", title),
                div(cls("nr-card-h"), h2(title), actions.isEmpty() ? null : div(cls("nr-row"), actions)),
                div(cls("nr-card-b", "nr-flush"), body));
    }

    public static Node grid(Object... cells) {
        return div(cls("nr-grid"), cells);
    }

    public static Node stack(Object... items) {
        return div(cls("nr-stack"), items);
    }

    public static Node row(Object... items) {
        return div(cls("nr-row"), items);
    }

    // ── content ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** A metric tile: label, value with unit, and an optional footer (delta, sparkline). */
    public static Node stat(String label, Object value, String unit, Object... foot) {
        return div(cls("nr-card", "nr-stat"), role("group"), aria("label", label),
                div(cls("nr-stat-label"), label),
                div(cls("nr-stat-value"), text(value), unit == null || unit.isEmpty() ? null : span(cls("nr-stat-unit"), unit)),
                foot.length == 0 ? null : div(cls("nr-stat-foot"), foot));
    }

    public static Node badge(String text, Tone tone) {
        return span(cls("nr-badge", toneClass("nr-badge", tone)), text);
    }

    public static Node alert(Tone tone, String title, Object... body) {
        String variant = switch (tone) {
            case OK -> "nr-alert-success";
            case WARN -> "nr-alert-warning";
            case ERROR -> "nr-alert-error";
            default -> null;
        };
        return div(cls("nr-alert", variant), role(tone == Tone.ERROR ? "alert" : "status"),
                title == null ? null : strong(title), body);
    }

    /** A definition list of label → value (values may be nodes). */
    public static Node keyValues(Map<String, ?> entries) {
        List<Node> items = new ArrayList<>();
        entries.forEach((k, v) -> {
            items.add(dt(k));
            items.add(dd(v instanceof Node n ? n : text(v)));
        });
        return dl(cls("nr-kv"), items);
    }

    public static Node empty(String message, Object... actions) {
        return div(cls("nr-empty"), p(message), actions);
    }

    /** One step of a breadcrumb trail. */
    public record Crumb(String label, String href) {
    }

    /** Breadcrumbs; the last one is the current page. */
    public static Node crumbs(List<Crumb> trail) {
        List<Node> items = new ArrayList<>();
        for (int i = 0; i < trail.size(); i++) {
            Crumb c = trail.get(i);
            boolean last = i == trail.size() - 1;
            items.add(li(last ? span(aria("current", "page"), c.label()) : a(href(c.href()), c.label())));
        }
        return nav(aria("label", "Breadcrumb"), ol(cls("nr-crumbs"), items));
    }

    /** A progress meter with thresholds (low/high are where it turns amber/red when {@code higherIsWorse}). */
    public static Node meter(String label, double value, double max, double low, double high) {
        return el("meter", cls("nr-meter"), aria("label", label), attr("min", 0), attr("max", max), attr("low", low),
                attr("high", high), attr("optimum", 0), attr("value", value), text(Math.round(value / max * 100) + "%"));
    }

    /**
     * An image framed in the media style: soft blurred vignette at the edges, slow zoom on hover. {@code url} is normally
     * a blob URL ({@code r.blob(...)}) or a same-site path; {@code caption} may be null.
     */
    public static Node media(String url, String alt, String caption) {
        return figure(cls("nr-media"), img(src(url), alt(alt), attr("loading", "lazy"), attr("decoding", "async")),
                caption == null ? null : figcaption(caption));
    }

    /** A grid of {@link #media} tiles sharing hairline borders. */
    public static Node gallery(Object... tiles) {
        return div(cls("nr-gallery"), role("list"), tiles);
    }

    public static Node mono(Object value) {
        return span(cls("nr-mono"), text(value));
    }

    /** Copy-to-clipboard button (a runtime behaviour, no server round trip). */
    public static Node copyButton(String label, String textToCopy) {
        return el("button", type("button"), cls("nr-btn", "nr-btn-s", "nr-btn-ghost"), attr("data-nr-b", "copy"),
                attr("data-nr-copy", textToCopy), aria("label", label), "Copy");
    }

    // ── controls ──────────────────────────────────────────────────────────────────────────────────────────────────────

    public enum Variant { DEFAULT, PRIMARY, DANGER, GHOST }

    /** A button; give it an action with {@code r.onClick(...)} among the parts. */
    public static Node button(String label, Variant variant, Object... parts) {
        return el("button", type("button"), cls("nr-btn", switch (variant) {
            case PRIMARY -> "nr-btn-primary";
            case DANGER -> "nr-btn-danger";
            case GHOST -> "nr-btn-ghost";
            default -> null;
        }), parts, label);
    }

    public static Node submit(String label, Variant variant, Object... parts) {
        return el("button", type("submit"), cls("nr-btn", variant == Variant.PRIMARY ? "nr-btn-primary"
                : variant == Variant.DANGER ? "nr-btn-danger" : null), parts, label);
    }

    /** Asks the user to confirm before the action of the element (or the form) is sent. */
    public static Attr confirm(String question) {
        return attr("data-nr-confirm", question);
    }

    public static Node linkButton(String label, String url, Variant variant) {
        return a(href(url), cls("nr-btn", variant == Variant.PRIMARY ? "nr-btn-primary" : variant == Variant.GHOST ? "nr-btn-ghost" : null), label);
    }

    /** A labelled text input. */
    public static Node textField(String name, String label, String value, Object... parts) {
        String id = "f-" + name;
        return label(cls("nr-field"), forId(id), span(label),
                input(id(id), name(name), type("text"), value == null ? null : value(value), parts));
    }

    public static Node passwordField(String name, String label, Object... parts) {
        String id = "f-" + name;
        return label(cls("nr-field"), forId(id), span(label), input(id(id), name(name), type("password"), parts));
    }

    public static Node textArea(String name, String label, String value, Object... parts) {
        String id = "f-" + name;
        return label(cls("nr-field"), forId(id), span(label), textarea(id(id), name(name), parts, value == null ? "" : value));
    }

    /** A labelled select; options are value → label, {@code selected} is a value. */
    public static Node selectField(String name, String label, Map<String, String> options, String selected, Object... parts) {
        String id = "f-" + name;
        List<Node> opts = new ArrayList<>();
        options.forEach((v, l) -> opts.add(option(value(v), v.equals(selected) ? selected() : null, l)));
        return label(cls("nr-field"), forId(id), span(label), select(id(id), name(name), parts, opts));
    }

    public static Node checkbox(String name, String label, boolean on, Object... parts) {
        return label(cls("nr-check"), input(type("checkbox"), name(name), on ? checked() : null, parts), span(label));
    }

    // ── overlays, menus, structure ────────────────────────────────────────────────────────────────────────────────────

    /**
     * A modal dialog (native {@code <dialog>}), opened by {@link #dialogButton} with the same id. Put actions in
     * {@code footer}; buttons there may carry {@code data-nr-b="dialog-close"} to close without a round trip.
     */
    public static Node dialog(String id, String title, List<?> body, List<?> footer) {
        return el("dialog", id(id), aria("label", title),
                div(cls("nr-card-h"), h2(title), el("button", type("button"), cls("nr-btn", "nr-btn-ghost", "nr-btn-s"),
                        attr("data-nr-b", "dialog-close"), aria("label", "Close"), "✕")),
                div(cls("nr-card-b"), body),
                footer.isEmpty() ? null : div(cls("nr-dialog-foot"), footer));
    }

    public static Node dialogButton(String label, String dialogId, Variant variant) {
        return el("button", type("button"), cls("nr-btn", variant == Variant.PRIMARY ? "nr-btn-primary" : null),
                attr("data-nr-b", "dialog-open"), attr("data-nr-target", dialogId), label);
    }

    /** A button that closes the dialog it is in (no server round trip). */
    public static Node dialogClose(String label) {
        return el("button", type("button"), cls("nr-btn"), attr("data-nr-b", "dialog-close"), label);
    }

    /** One entry of a {@link #menu}: a label and the attributes of its button (normally an action). */
    public record MenuItem(String label, Object... parts) {
    }

    /** A dropdown menu on {@code <details>}: works without JavaScript and closes when its region re-renders. */
    public static Node menu(String label, List<MenuItem> items) {
        List<Node> lis = new ArrayList<>();
        for (MenuItem i : items) lis.add(li(el("button", type("button"), role("menuitem"), i.parts(), i.label())));
        return details(cls("nr-menu"), summary(cls("nr-btn", "nr-btn-s"), aria("haspopup", "menu"), label, " ▾"),
                ul(cls("nr-menu-list"), role("menu"), lis));
    }

    /** A node of a {@link #tree}: a label (text or node) and children. */
    public record TreeItem(Object label, List<TreeItem> children) {
        public static TreeItem leaf(Object label) {
            return new TreeItem(label, List.of());
        }
    }

    /** A collapsible tree; branches up to {@code openDepth} levels start open. */
    public static Node tree(List<TreeItem> roots, int openDepth) {
        return ul(cls("nr-tree"), role("tree"), treeItems(roots, 0, openDepth));
    }

    private static List<Node> treeItems(List<TreeItem> items, int depth, int openDepth) {
        List<Node> out = new ArrayList<>();
        for (TreeItem t : items) {
            out.add(t.children().isEmpty()
                    ? li(role("treeitem"), span(t.label()))
                    : li(role("treeitem"), details(depth < openDepth ? open() : null, summary(t.label()),
                    ul(role("group"), treeItems(t.children(), depth + 1, openDepth)))));
        }
        return out;
    }

    /** One event on a {@link #timeline}. */
    public record TimelineEntry(Instant time, String title, Object body) {
    }

    /** Events, newest first, with their time and an optional body. */
    public static Node timeline(List<TimelineEntry> entries) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneOffset.UTC);
        List<Node> items = new ArrayList<>();
        for (TimelineEntry e : entries) {
            items.add(li(el("time", attr("datetime", e.time().toString()), f.format(e.time()) + " UTC"), strong(e.title()),
                    e.body() == null ? null : div(cls("nr-muted"), e.body())));
        }
        return ol(cls("nr-timeline"), items);
    }

    /**
     * Previous / next navigation over {@code pages} (0-based {@code page}). {@code go} receives the page to show;
     * keep the page number in the calling component's state.
     */
    public static Node pagination(Render r, int page, int pages, IntConsumer go) {
        if (pages <= 1) return null;
        return nav(cls("nr-pager"), aria("label", "Pages"), span(cls("nr-num"), "Page " + (page + 1) + " of " + pages),
                div(cls("nr-row"),
                        el("button", type("button"), cls("nr-btn", "nr-btn-s"), page == 0 ? disabled() : null,
                                r.onViewClick(() -> go.accept(Math.max(0, page - 1))), "← Previous"),
                        el("button", type("button"), cls("nr-btn", "nr-btn-s"), page >= pages - 1 ? disabled() : null,
                                r.onViewClick(() -> go.accept(Math.min(pages - 1, page + 1))), "Next →")));
    }

    static String toneClass(String prefix, Tone tone) {
        return switch (tone) {
            case OK -> prefix + "-ok";
            case WARN -> prefix + "-warn";
            case ERROR -> prefix + "-err";
            case INFO -> prefix + "-info";
            default -> null;
        };
    }

    /** Human-readable byte count (binary units). */
    public static String bytes(long n) {
        if (n < 1024) return n + " B";
        String[] u = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double v = n;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return (v >= 100 ? String.format("%.0f", v) : v >= 10 ? String.format("%.1f", v) : String.format("%.2f", v)) + " " + u[i];
    }

    /** Human-readable count (1.2k, 3.4M). */
    public static String count(long n) {
        if (Math.abs(n) < 1000) return Long.toString(n);
        String[] u = {"k", "M", "G", "T"};
        double v = n;
        int i = -1;
        while (Math.abs(v) >= 1000 && i < u.length - 1) {
            v /= 1000;
            i++;
        }
        return String.format(Math.abs(v) >= 100 ? "%.0f%s" : "%.1f%s", v, u[i]);
    }
}
