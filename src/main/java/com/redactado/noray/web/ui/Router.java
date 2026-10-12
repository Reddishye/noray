package com.redactado.noray.web.ui;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** The routes of one app, matched against app-relative paths. */
public final class Router implements Routes {

    public record NavEntry(String label, String appPath) {
    }

    public sealed interface Target permits PageTarget, DownloadTarget {
    }

    public record PageTarget(Function<RequestContext, Page> handler) implements Target {
    }

    public record DownloadTarget(Function<RequestContext, Download> handler) implements Target {
    }

    public record Match(Target target, Map<String, String> params) {
    }

    private record Route(List<String> segments, Target target) {
    }

    private final List<Route> routes = new ArrayList<>();
    private final List<NavEntry> nav = new ArrayList<>();

    @Override
    public synchronized Routes page(String pattern, Function<RequestContext, Page> handler) {
        routes.add(new Route(compile(pattern), new PageTarget(handler)));
        return this;
    }

    @Override
    public synchronized Routes download(String pattern, Function<RequestContext, Download> handler) {
        routes.add(new Route(compile(pattern), new DownloadTarget(handler)));
        return this;
    }

    @Override
    public synchronized Routes nav(String label, String appPath) {
        nav.add(new NavEntry(label, appPath));
        return this;
    }

    public synchronized List<NavEntry> navEntries() {
        return List.copyOf(nav);
    }

    /** The first route matching {@code path} (already normalised by {@link #normalise}). */
    public synchronized Optional<Match> match(String path) {
        List<String> parts = split(path);
        for (Route r : routes) {
            Map<String, String> params = new LinkedHashMap<>();
            if (matches(r.segments, parts, params)) {
                return Optional.of(new Match(r.target, params));
            }
        }
        return Optional.empty();
    }

    private static boolean matches(List<String> pattern, List<String> parts, Map<String, String> params) {
        for (int i = 0; i < pattern.size(); i++) {
            String seg = pattern.get(i);
            if (seg.startsWith("{*")) {
                params.put(seg.substring(2, seg.length() - 1), String.join("/", parts.subList(Math.min(i, parts.size()), parts.size())));
                return true;
            }
            if (i >= parts.size()) return false;
            if (seg.startsWith("{")) {
                params.put(seg.substring(1, seg.length() - 1), parts.get(i));
            } else if (!seg.equals(parts.get(i))) {
                return false;
            }
        }
        return pattern.size() == parts.size();
    }

    private static List<String> compile(String pattern) {
        List<String> segs = split(pattern);
        for (int i = 0; i < segs.size(); i++) {
            String s = segs.get(i);
            if (s.startsWith("{*") && i != segs.size() - 1) {
                throw new IllegalArgumentException("{*rest} must be the last segment: " + pattern);
            }
            if (s.startsWith("{") && !s.matches("\\{\\*?[a-zA-Z][a-zA-Z0-9]*}")) {
                throw new IllegalArgumentException("bad parameter segment '" + s + "' in " + pattern);
            }
        }
        return segs;
    }

    private static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /**
     * Decodes a raw request path once and normalises it: no empty or {@code .} segments; {@code ..}, NUL, backslashes
     * and encoded slashes are refused (empty result) instead of resolved, so a handler never sees a traversal.
     */
    public static Optional<String> normalise(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.charAt(0) != '/') return Optional.empty();
        if (rawPath.contains("%2f") || rawPath.contains("%2F") || rawPath.contains("%5c") || rawPath.contains("%5C")) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (decoded.indexOf('\0') >= 0 || decoded.indexOf('\\') >= 0) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        for (String s : decoded.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) return Optional.empty();
            sb.append('/').append(s);
        }
        return Optional.of(sb.isEmpty() ? "/" : sb.toString());
    }
}
