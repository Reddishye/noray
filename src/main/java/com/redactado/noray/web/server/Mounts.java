package com.redactado.noray.web.server;

import com.redactado.noray.web.Site;
import com.redactado.noray.web.WebApps;
import com.redactado.noray.web.ui.Router;
import com.redactado.noray.web.ui.WebApp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Every mounted app and site. The unscoped {@link WebApps} implementation, and the factory of bound views. */
public final class Mounts implements WebApps {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,62}");
    private static final Pattern PREFIX = Pattern.compile("/|(/[a-z0-9][a-z0-9_.-]{0,62})+");

    record MountedApp(String slug, String owner, WebApp app, Router router) {
    }

    record MountedSite(String prefix, String owner, Site site) {
    }

    private final Map<String, MountedApp> apps = new ConcurrentHashMap<>();
    private final Map<String, MountedSite> sites = new ConcurrentHashMap<>();

    @Override
    public Mount mountApp(String slug, WebApp app) {
        return mountApp(slug, app, "core");
    }

    @Override
    public Mount mountSite(String prefix, Site site) {
        return mountSite(prefix, site, "core");
    }

    Mount mountApp(String slug, WebApp app, String owner) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("app slug must be lower-case letters, digits, . _ - : " + slug);
        }
        Router router = new Router();
        app.routes(router);
        MountedApp m = new MountedApp(slug, owner, app, router);
        if (apps.putIfAbsent(slug, m) != null) {
            throw new IllegalStateException("an app is already mounted at /apps/" + slug);
        }
        String path = "/apps/" + slug;
        return new Mount() {
            @Override public String path() { return path; }
            @Override public void close() { apps.remove(slug, m); }
        };
    }

    Mount mountSite(String prefix, Site site, String owner) {
        if (prefix == null || !PREFIX.matcher(prefix).matches() || prefix.startsWith("/_nr") || prefix.equals("/apps")
                || prefix.startsWith("/apps/")) {
            throw new IllegalArgumentException("site prefix must look like /name[/name...] (not /_nr or /apps): " + prefix);
        }
        MountedSite m = new MountedSite(prefix, owner, site);
        if (sites.putIfAbsent(prefix, m) != null) {
            throw new IllegalStateException("a site is already mounted at " + prefix);
        }
        return new Mount() {
            @Override public String path() { return prefix; }
            @Override public void close() { sites.remove(prefix, m); }
        };
    }

    @Override
    public List<AppInfo> apps() {
        List<AppInfo> out = new ArrayList<>();
        for (MountedApp m : apps.values()) out.add(new AppInfo(m.slug, m.app.title(), m.owner));
        out.sort(Comparator.comparing((AppInfo a) -> !a.owner().equals("core")).thenComparing(AppInfo::title));
        return out;
    }

    Optional<MountedApp> app(String slug) {
        return Optional.ofNullable(apps.get(slug));
    }

    List<MountedApp> mountedApps() {
        List<MountedApp> out = new ArrayList<>(apps.values());
        out.sort(Comparator.comparing((MountedApp a) -> !a.owner.equals("core")).thenComparing(a -> a.app.title()));
        return out;
    }

    /** The site with the longest prefix covering {@code path}, and the path relative to it. */
    Optional<Map.Entry<MountedSite, String>> site(String path) {
        MountedSite best = null;
        for (MountedSite s : sites.values()) {
            boolean covers = s.prefix.equals("/") || path.equals(s.prefix) || path.startsWith(s.prefix + "/");
            if (covers && (best == null || s.prefix.length() > best.prefix.length())) best = s;
        }
        if (best == null) return Optional.empty();
        String rest = best.prefix.equals("/") ? path : path.substring(best.prefix.length());
        return Optional.of(Map.entry(best, rest.isEmpty() ? "/" : rest));
    }

    /**
     * The view a sandboxed module receives: apps always at {@code /apps/<moduleId>}, sites only under granted prefixes.
     *
     * @param mayMountApp  the module holds {@code web.app}
     * @param sitePrefixes the prefixes of its {@code web.site:} grants
     */
    public WebApps bound(String moduleId, boolean mayMountApp, List<String> sitePrefixes) {
        return new WebApps() {
            @Override
            public Mount mountApp(String ignoredSlug, WebApp app) {
                if (!mayMountApp) {
                    throw new SecurityException("module [" + moduleId + "] lacks web.app");
                }
                return Mounts.this.mountApp(slugOf(moduleId), app, moduleId);
            }

            @Override
            public Mount mountSite(String prefix, Site site) {
                boolean covered = sitePrefixes.stream().anyMatch(g -> g.equals("/") || prefix.equals(g) || prefix.startsWith(g + "/"));
                if (!covered) {
                    throw new SecurityException("module [" + moduleId + "] lacks web.site:" + prefix);
                }
                return Mounts.this.mountSite(prefix, site, moduleId);
            }

            @Override
            public List<AppInfo> apps() {
                return Mounts.this.apps();
            }
        };
    }

    /** A module id as a URL segment ({@code com.example.tool} stays as is; anything else is replaced by '-'). */
    static String slugOf(String moduleId) {
        String s = moduleId.toLowerCase().replaceAll("[^a-z0-9_.-]", "-");
        if (s.isEmpty() || !Character.isLetterOrDigit(s.charAt(0))) s = "m" + s;
        return s.length() > 63 ? s.substring(0, 63) : s;
    }
}
