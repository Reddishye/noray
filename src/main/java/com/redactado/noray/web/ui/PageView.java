package com.redactado.noray.web.ui;

import com.redactado.noray.web.html.Attr;
import com.redactado.noray.web.html.Html;
import com.redactado.noray.web.html.Node;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One open page: its regions (units of re-rendering), the actions bound while rendering them, and the timed regions
 * due for a push. The page body is region {@code r0}. An action re-renders the region it was created in; regions
 * nested in it are kept (same component instance, same id) or dropped (gone from the new render), and their old
 * actions are forgotten, so a stale token can never run.
 *
 * <p>Thread safety: every entry point is synchronized; one page handles one event at a time.
 */
public final class PageView {

    /** A region's new HTML. */
    public record Patch(String region, String html) {
    }

    /** What the browser must do after an event: patches, maybe a navigation, maybe notifications. */
    public record Result(List<Patch> patches, String navigate, List<Toast> toasts) {
    }

    /** Server services a render needs (links inside the app, blob URLs, request information). */
    public interface Environment {
        String link(String appPath);

        String blob(Blob blob);

        RequestContext request();

        /** Where no-JavaScript form posts go for this page (the action token is appended). */
        String formEndpoint();

        /** Where file-upload forms post (the action token is appended). */
        default String uploadEndpoint() {
            return "/_nr/upload?t=";
        }

        /** False for public pages: they keep no server state, so binding an action is a programming error. */
        default boolean interactive() {
            return true;
        }
    }

    private static final System.Logger LOG = System.getLogger("noray.web");
    static final int MAX_ACTIONS = 20_000;
    static final long SLOW_RENDER_MS = 200;
    static final int MAX_REGIONS = 2_000;
    private static final Duration MIN_INTERVAL = Duration.ofMillis(500);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String id;
    private final Page page;
    private final Environment env;
    private final Map<String, Region> regions = new LinkedHashMap<>();
    private final Map<Component, String> regionOf = new IdentityHashMap<>();
    private final Map<String, Action> actions = new HashMap<>();
    private int nextRegion;
    private volatile long lastSeenNanos = System.nanoTime();

    private static final class Region {
        final String id;
        final String parent;
        final Component component;
        Duration every;
        Instant due;
        final Set<String> tokens = new HashSet<>();
        boolean seen;

        Region(String id, String parent, Component component) {
            this.id = id;
            this.parent = parent;
            this.component = component;
        }
    }

    private record Action(String region, String event, Consumer<Event> callback, boolean viewOnly) {
    }

    public PageView(Page page, Environment env) {
        this.id = token();
        this.page = page;
        this.env = env;
    }

    public String id() {
        return id;
    }

    public String title() {
        return page.title();
    }

    /** Marks the page as still open (any event or stream activity). */
    public void touch() {
        lastSeenNanos = System.nanoTime();
    }

    public Duration idle() {
        return Duration.ofNanos(System.nanoTime() - lastSeenNanos);
    }

    /** First render of the whole body. */
    public synchronized Node renderBody() {
        touch();
        regions.clear();
        regionOf.clear();
        actions.clear();
        Region root = new Region("r0", null, page.body());
        regions.put(root.id, root);
        regionOf.put(page.body(), root.id);
        return renderRegion(root);
    }

    /** Runs the action behind {@code token}; unknown or stale tokens do nothing and return no patches. */
    public synchronized Result dispatch(String token, String value, FormData form) {
        return dispatch(token, value, form, true);
    }

    /** @param operator false for read-only viewers: only view actions ({@link Render#onView}) run */
    public synchronized Result dispatch(String token, String value, FormData form, boolean operator) {
        touch();
        Action a = actions.get(token);
        if (a == null) {
            return new Result(List.of(), null, List.of());
        }
        if (!operator && !a.viewOnly()) {
            return new Result(List.of(), null, List.of(new Toast(Toast.Level.WARNING, "Read-only access: this needs an operator token")));
        }
        Ctx ctx = new Ctx();
        try {
            a.callback().accept(new Event(a.event(), value == null ? "" : value, form == null ? FormData.EMPTY : form, ctx));
        } catch (RuntimeException e) {
            // the region is still re-rendered below: the action may have changed state before failing
            LOG.log(System.Logger.Level.WARNING, "action failed on page " + page.title(), e);
            ctx.navigate = null;
            ctx.toast(Toast.Level.ERROR, "Action failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        if (ctx.navigate != null) {
            return new Result(List.of(), ctx.navigate, ctx.toasts);
        }
        String target = ctx.refreshPage ? "r0" : a.region();
        Region r = regions.get(target);
        List<Patch> patches = r == null ? List.of() : List.of(rerender(r));
        return new Result(patches, null, ctx.toasts);
    }

    /** Regions whose interval has elapsed, re-rendered. */
    public synchronized List<Patch> due(Instant now) {
        List<Patch> out = new ArrayList<>();
        for (Region r : List.copyOf(regions.values())) {
            if (r.every == null || r.due == null || now.isBefore(r.due) || !regions.containsKey(r.id)) continue;
            r.due = now.plus(r.every);
            if (isAncestorPatched(r, out)) continue; // already part of a bigger patch in this round
            out.add(rerender(r));
        }
        return out;
    }

    /** True when some region has an interval (the server keeps a stream open only for those pages). */
    public synchronized boolean hasTimedRegions() {
        return regions.values().stream().anyMatch(r -> r.every != null);
    }

    public synchronized int actionCount() {
        return actions.size();
    }

    // ── rendering ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private Patch rerender(Region r) {
        // forget everything below r; regions rendered again are marked seen, the rest are dropped afterwards
        List<Region> below = descendants(r.id);
        for (Region d : below) {
            d.seen = false;
            d.tokens.forEach(actions::remove);
            d.tokens.clear();
        }
        r.tokens.forEach(actions::remove);
        r.tokens.clear();
        Node html = renderRegion(r);
        for (Region d : below) {
            if (!d.seen) {
                regions.remove(d.id);
                regionOf.remove(d.component);
            }
        }
        return new Patch(r.id, html.html());
    }

    private Node renderRegion(Region region) {
        Renderer renderer = new Renderer(region);
        Node body;
        long t0 = System.nanoTime();
        try {
            body = region.component.render(renderer);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ms > SLOW_RENDER_MS) {
                LOG.log(System.Logger.Level.WARNING, "slow region " + region.id + " on page '"
                        + page.title() + "': " + ms + " ms (" + region.component.getClass().getName() + ")");
            }
        } catch (RuntimeException e) {
            body = Html.div(Html.cls("nr-alert", "nr-alert-error"), Html.role("alert"),
                    "This part of the page failed to render: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        region.seen = true;
        return Html.div(Html.cls("nr-region"), Html.data("nr-region", region.id), body);
    }

    private List<Region> descendants(String rootId) {
        List<Region> out = new ArrayList<>();
        Deque<String> todo = new ArrayDeque<>(List.of(rootId));
        while (!todo.isEmpty()) {
            String p = todo.pop();
            for (Region r : regions.values()) {
                if (p.equals(r.parent)) {
                    out.add(r);
                    todo.push(r.id);
                }
            }
        }
        return out;
    }

    private boolean isAncestorPatched(Region r, List<Patch> patched) {
        for (String p = r.parent; p != null; p = regions.containsKey(p) ? regions.get(p).parent : null) {
            String pid = p;
            if (patched.stream().anyMatch(x -> x.region().equals(pid))) return true;
        }
        return false;
    }

    private final class Renderer implements Render {
        private final Region region;

        Renderer(Region region) {
            this.region = region;
        }

        @Override
        public Node live(Component c) {
            return live(c, null);
        }

        @Override
        public Node live(Component c, Duration every) {
            String existing = regionOf.get(c);
            Region child = existing == null ? null : regions.get(existing);
            if (child == null || !region.id.equals(child.parent)) {
                if (regions.size() >= MAX_REGIONS) {
                    throw new IllegalStateException("too many live regions on one page");
                }
                child = new Region("r" + (++nextRegion), region.id, c);
                regions.put(child.id, child);
                regionOf.put(c, child.id);
            }
            if (every != null && env.interactive()) {
                child.every = every.compareTo(MIN_INTERVAL) < 0 ? MIN_INTERVAL : every;
                if (child.due == null) child.due = Instant.now().plus(child.every);
            }
            return renderRegion(child);
        }

        @Override
        public Attr on(String event, Consumer<Event> callback) {
            return attr(event, callback, false);
        }

        @Override
        public Attr onView(String event, Consumer<Event> callback) {
            return attr(event, callback, true);
        }

        private Attr attr(String event, Consumer<Event> callback, boolean viewOnly) {
            if (!event.matches("click|change|input|submit|toggle")) {
                throw new IllegalArgumentException("unsupported event: " + event);
            }
            return new Attr("data-nr-on", event + ":" + bind(event, callback, viewOnly));
        }

        @Override
        public Attr[] onSubmit(Consumer<Event> callback) {
            String t = bind("submit", callback, false);
            return new Attr[]{new Attr("data-nr-on", "submit:" + t), new Attr("method", "post"),
                    new Attr("action", env.formEndpoint() + t)};
        }

        @Override
        public Attr[] onUpload(Consumer<Event> callback) {
            String t = bind("submit", callback, false);
            return new Attr[]{new Attr("data-nr-on", "submit:" + t), new Attr("method", "post"),
                    new Attr("enctype", "multipart/form-data"), new Attr("action", env.uploadEndpoint() + t)};
        }

        private String bind(String event, Consumer<Event> callback, boolean viewOnly) {
            if (!env.interactive()) {
                throw new IllegalStateException("public pages cannot have actions (they keep no server state)");
            }
            if (actions.size() >= MAX_ACTIONS) {
                throw new IllegalStateException("too many actions on one page");
            }
            String t = token();
            actions.put(t, new Action(region.id, event, callback, viewOnly));
            region.tokens.add(t);
            return t;
        }

        @Override
        public String link(String appPath) {
            return env.link(appPath);
        }

        @Override
        public String blob(Blob blob) {
            return env.blob(blob);
        }

        @Override
        public RequestContext request() {
            return env.request();
        }
    }

    private static final class Ctx implements ActionContext {
        boolean refreshPage;
        String navigate;
        final List<Toast> toasts = new ArrayList<>();

        @Override public void refreshPage() { refreshPage = true; }

        @Override public void navigate(String url) {
            Html.href(url); // same validation as a link
            navigate = url;
        }

        @Override public void toast(Toast.Level level, String message) {
            if (toasts.size() < 8) toasts.add(new Toast(level, message));
        }
    }

    static String token() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
