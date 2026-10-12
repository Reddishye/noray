package com.redactado.noray.web.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Design tokens. The kit stylesheet uses only the CSS custom properties generated here, so a theme is a value, not a
 * stylesheet: swap colours or density without touching a component. Light and dark follow the system preference and an
 * explicit {@code data-theme} on {@code <html>}. Only system font stacks: pages never fetch anything from elsewhere.
 */
public record Theme(Map<String, String> light, Map<String, String> dark, Map<String, String> shared) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9-]+");
    private static final Pattern VALUE = Pattern.compile("[^;{}<>\\\\]*"); // a value can never close the rule or the block

    public Theme {
        light = check(light);
        dark = check(dark);
        shared = check(shared);
    }

    private static Map<String, String> check(Map<String, String> m) {
        Map<String, String> copy = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (!NAME.matcher(k).matches() || !VALUE.matcher(v).matches()) {
                throw new IllegalArgumentException("bad theme token " + k + ": " + v);
            }
            copy.put(k, v);
        });
        return Collections.unmodifiableMap(copy);
    }

    /** Harbour: deep navy and brass. Contrast of ink on bg/surface is above 7:1 in both modes; muted text above 4.5:1. */
    public static Theme harbour() {
        Map<String, String> shared = new LinkedHashMap<>();
        shared.put("sans", "ui-sans-serif,system-ui,-apple-system,\"Segoe UI\",Roboto,\"Helvetica Neue\",Arial,sans-serif");
        shared.put("display", "ui-rounded,\"SF Pro Rounded\",ui-sans-serif,system-ui,-apple-system,\"Segoe UI\",sans-serif");
        shared.put("mono", "ui-monospace,\"SF Mono\",\"JetBrains Mono\",\"Cascadia Code\",Menlo,Consolas,monospace");
        shared.put("text", "15px");
        shared.put("radius", "10px");
        shared.put("radius-s", "7px");
        shared.put("s1", ".25rem");
        shared.put("s2", ".5rem");
        shared.put("s3", ".75rem");
        shared.put("s4", "1rem");
        shared.put("s5", "1.5rem");
        shared.put("s6", "2rem");
        shared.put("t", ".18s");
        shared.put("t-slow", ".7s");
        shared.put("side-w", "15rem");
        shared.put("content-w", "84rem");
        shared.put("side", "#0d1b2e");
        shared.put("side-ink", "#e9eef6");
        shared.put("side-muted", "#8fa3bf");
        shared.put("side-line", "#1b2c44");
        shared.put("side-hover", "rgba(255,255,255,.06)");
        shared.put("side-active", "rgba(212,169,74,.16)");

        Map<String, String> light = new LinkedHashMap<>();
        light.put("bg", "#f6f4ee");
        light.put("surface", "#ffffff");
        light.put("surface-2", "#faf8f3");
        light.put("field", "#ffffff");
        light.put("ink", "#13202f");
        light.put("muted", "#56657a");
        light.put("line", "#e4e0d6");
        light.put("line-strong", "#cbc5b8");
        light.put("tint", "rgba(19,32,47,.05)");
        light.put("link", "#1d4f91");
        light.put("accent", "#a87a1c");
        light.put("accent-ink", "#8a6212");
        light.put("on-accent", "#ffffff");
        light.put("ok", "#1f7a4d");
        light.put("ok-bg", "#e3f3ea");
        light.put("warn", "#9a5b00");
        light.put("warn-bg", "#fbefd9");
        light.put("err", "#b42318");
        light.put("err-bg", "#fde8e6");
        light.put("info", "#1d4f91");
        light.put("info-bg", "#e5eefb");
        light.put("code-bg", "#fbfaf6");
        light.put("shadow", "0 1px 2px rgba(19,32,47,.06)");
        light.put("c1", "#1d4f91");
        light.put("c2", "#1f7a4d");
        light.put("c3", "#a87a1c");
        light.put("c4", "#7b3fa0");
        light.put("c5", "#b42318");
        light.put("c6", "#0f7c86");

        Map<String, String> dark = new LinkedHashMap<>();
        dark.put("bg", "#0a1422");
        dark.put("surface", "#101d30");
        dark.put("surface-2", "#13233a");
        dark.put("field", "#0c1828");
        dark.put("ink", "#e8edf5");
        dark.put("muted", "#9aabc3");
        dark.put("line", "#1f3049");
        dark.put("line-strong", "#2e4566");
        dark.put("tint", "rgba(232,237,245,.06)");
        dark.put("link", "#8db8f2");
        dark.put("accent", "#d4a94a");
        dark.put("accent-ink", "#e6c272");
        dark.put("on-accent", "#14100a");
        dark.put("ok", "#5fd39a");
        dark.put("ok-bg", "rgba(95,211,154,.12)");
        dark.put("warn", "#f0b85a");
        dark.put("warn-bg", "rgba(240,184,90,.12)");
        dark.put("err", "#ff8a7f");
        dark.put("err-bg", "rgba(255,138,127,.12)");
        dark.put("info", "#8db8f2");
        dark.put("info-bg", "rgba(141,184,242,.12)");
        dark.put("code-bg", "#0c1828");
        dark.put("shadow", "none");
        dark.put("c1", "#8db8f2");
        dark.put("c2", "#5fd39a");
        dark.put("c3", "#d4a94a");
        dark.put("c4", "#c49cf0");
        dark.put("c5", "#ff8a7f");
        dark.put("c6", "#5ccfd9");
        return new Theme(light, dark, shared);
    }

    /**
     * Noir (the default): pure black in dark mode and pure white in light mode, hairline borders, square corners, no
     * shadows, monochrome accents with muted status colours. Contrast of ink and muted text is above 7:1 and 4.5:1.
     */
    public static Theme noir() {
        Map<String, String> shared = new LinkedHashMap<>();
        shared.put("sans", "\"Inter\",\"Geist\",ui-sans-serif,system-ui,-apple-system,\"Segoe UI\",Roboto,\"Helvetica Neue\",Arial,sans-serif");
        shared.put("display", "\"Inter\",\"Geist\",ui-sans-serif,system-ui,-apple-system,\"Segoe UI\",sans-serif");
        shared.put("mono", "\"Geist Mono\",\"JetBrains Mono\",ui-monospace,\"SF Mono\",\"Cascadia Code\",Menlo,Consolas,monospace");
        shared.put("text", "15px");
        shared.put("radius", "0");
        shared.put("radius-s", "0");
        shared.put("s1", ".25rem");
        shared.put("s2", ".5rem");
        shared.put("s3", ".75rem");
        shared.put("s4", "1rem");
        shared.put("s5", "1.5rem");
        shared.put("s6", "2.25rem");
        shared.put("t", ".18s");
        shared.put("t-slow", ".9s");
        shared.put("side-w", "15.5rem");
        shared.put("content-w", "90rem");

        Map<String, String> light = new LinkedHashMap<>();
        light.put("bg", "#ffffff");
        light.put("surface", "#ffffff");
        light.put("surface-2", "#f5f5f5");
        light.put("field", "#ffffff");
        light.put("ink", "#000000");
        light.put("muted", "#5c5c5c");
        light.put("line", "#e6e6e6");
        light.put("line-strong", "#c4c4c4");
        light.put("tint", "rgba(0,0,0,.04)");
        light.put("link", "#000000");
        light.put("accent", "#000000");
        light.put("accent-ink", "#000000");
        light.put("on-accent", "#ffffff");
        light.put("ok", "#0a7d3e");
        light.put("ok-bg", "rgba(10,125,62,.08)");
        light.put("warn", "#9a5a00");
        light.put("warn-bg", "rgba(154,90,0,.08)");
        light.put("err", "#c4201b");
        light.put("err-bg", "rgba(196,32,27,.08)");
        light.put("info", "#1f4fd1");
        light.put("info-bg", "rgba(31,79,209,.08)");
        light.put("code-bg", "#fafafa");
        light.put("shadow", "none");
        light.put("side", "#ffffff");
        light.put("side-ink", "#000000");
        light.put("side-muted", "#6b6b6b");
        light.put("side-line", "#e6e6e6");
        light.put("side-hover", "#f5f5f5");
        light.put("side-active", "#f0f0f0");
        light.put("c1", "#000000");
        light.put("c2", "#0a7d3e");
        light.put("c3", "#9a5a00");
        light.put("c4", "#6b3fd4");
        light.put("c5", "#c4201b");
        light.put("c6", "#1f4fd1");

        Map<String, String> dark = new LinkedHashMap<>();
        dark.put("bg", "#000000");
        dark.put("surface", "#000000");
        dark.put("surface-2", "#0d0d0d");
        dark.put("field", "#050505");
        dark.put("ink", "#ffffff");
        dark.put("muted", "#8f8f8f");
        dark.put("line", "#1c1c1c");
        dark.put("line-strong", "#333333");
        dark.put("tint", "rgba(255,255,255,.05)");
        dark.put("link", "#ffffff");
        dark.put("accent", "#ffffff");
        dark.put("accent-ink", "#ffffff");
        dark.put("on-accent", "#000000");
        dark.put("ok", "#3ecf8e");
        dark.put("ok-bg", "rgba(62,207,142,.1)");
        dark.put("warn", "#f5a623");
        dark.put("warn-bg", "rgba(245,166,35,.1)");
        dark.put("err", "#ff5c57");
        dark.put("err-bg", "rgba(255,92,87,.1)");
        dark.put("info", "#7aa2ff");
        dark.put("info-bg", "rgba(122,162,255,.1)");
        dark.put("code-bg", "#050505");
        dark.put("shadow", "none");
        dark.put("side", "#000000");
        dark.put("side-ink", "#ffffff");
        dark.put("side-muted", "#8a8a8a");
        dark.put("side-line", "#1c1c1c");
        dark.put("side-hover", "#0d0d0d");
        dark.put("side-active", "#111111");
        dark.put("c1", "#ffffff");
        dark.put("c2", "#3ecf8e");
        dark.put("c3", "#f5a623");
        dark.put("c4", "#b18cff");
        dark.put("c5", "#ff5c57");
        dark.put("c6", "#7aa2ff");
        return new Theme(light, dark, shared);
    }

    /** A theme by name ({@code noir}, {@code harbour}); unknown names fall back to noir. */
    public static Theme named(String name) {
        return "harbour".equalsIgnoreCase(name) ? harbour() : noir();
    }

    /** The CSS custom properties: shared, light by default, dark by system preference or explicit choice. */
    public String css() {
        StringBuilder sb = new StringBuilder();
        sb.append(":root{");
        vars(sb, shared);
        vars(sb, light);
        sb.append("}\n@media (prefers-color-scheme:dark){:root:not([data-theme=\"light\"]){");
        vars(sb, dark);
        sb.append("}}\n:root[data-theme=\"dark\"]{");
        vars(sb, dark);
        sb.append("}\n");
        return sb.toString();
    }

    private static void vars(StringBuilder sb, Map<String, String> m) {
        m.forEach((k, v) -> sb.append("--nr-").append(k).append(':').append(v).append(';'));
    }
}
