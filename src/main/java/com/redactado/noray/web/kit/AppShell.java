package com.redactado.noray.web.kit;

import com.redactado.noray.web.html.Node;

import java.util.ArrayList;
import java.util.List;

import static com.redactado.noray.web.html.Html.*;

/** The frame of every authenticated page: brand, navigation of all mounted apps, top bar, content. */
public final class AppShell {

    /** One navigation group: an app's title and its entries (label, absolute URL). */
    public record NavGroup(String title, List<NavLink> links) {
    }

    public record NavLink(String label, String url) {
    }

    private AppShell() {
    }

    /**
     * @param currentPath the request path, to mark the current entry
     * @param logoutUrl   where the sign-out form posts, or null on public pages
     */
    public static Node render(String brand, List<NavGroup> groups, String currentPath, String pageTitle, String logoutUrl,
                              Node content) {
        List<Node> nav = new ArrayList<>();
        for (NavGroup g : groups) {
            List<Node> items = new ArrayList<>();
            for (NavLink l : g.links()) {
                boolean current = isCurrent(l.url(), currentPath);
                items.add(li(a(href(l.url()), current ? aria("current", "page") : null, l.label())));
            }
            nav.add(div(cls("nr-nav-group"), div(cls("nr-nav-title"), g.title()), ul(cls("nr-nav"), items)));
        }
        return div(cls("nr-shell"),
                aside(cls("nr-side"),
                        a(cls("nr-brand"), href("/"), span(cls("nr-brand-mark"), aria("hidden", "true"), "⚓"), brand),
                        nav(aria("label", "Main"), nav)),
                div(cls("nr-main"),
                        header(cls("nr-top"),
                                div(cls("nr-top-title"), pageTitle),
                                div(cls("nr-top-actions"),
                                        button(type("button"), cls("nr-btn", "nr-btn-ghost", "nr-btn-s"), attr("data-nr-b", "theme"),
                                                aria("label", "Switch light or dark theme"), "◐"),
                                        logoutUrl == null ? null : form(method("post"), action(logoutUrl),
                                                button(type("submit"), cls("nr-btn", "nr-btn-ghost", "nr-btn-s"), "Sign out")))),
                        main(cls("nr-content"), id("main"), content)));
    }

    /** Exact match for an app's root, prefix match for deeper entries. */
    static boolean isCurrent(String url, String path) {
        if (url.equals(path)) return true;
        String u = url.endsWith("/") ? url : url + "/";
        long depth = url.chars().filter(c -> c == '/').count();
        return depth > 3 && path.startsWith(u);
    }
}
