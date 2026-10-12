package com.redactado.noray.web.html;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every rule that keeps generated HTML free of injected script, in one place so it can be reviewed in isolation.
 * Defence in depth: the Content Security Policy blocks inline script anyway, but refusing it here turns a silent
 * breakage (or a CSP bypass nobody thought of) into an exception in tests.
 */
final class Safety {

    private static final Pattern TAG = Pattern.compile("[a-zA-Z][a-zA-Z0-9-]{0,31}");
    private static final Pattern ATTR = Pattern.compile("[a-zA-Z_:][-a-zA-Z0-9_:.]{0,63}");

    /** Elements that load or run code, change how URLs resolve, or are SVG script/animation vectors. */
    private static final Set<String> FORBIDDEN_TAGS = Set.of("script", "style", "iframe", "frame", "frameset", "object",
            "embed", "applet", "base", "link", "meta", "noscript", "template", "portal", "foreignobject", "animate", "set",
            "animatemotion", "animatetransform", "handler", "listener", "math", "xmp", "plaintext", "noembed");

    private static final Set<String> VOID = Set.of("area", "br", "col", "hr", "img", "input", "source", "track", "wbr");

    /** Attributes whose value is a URL the browser may navigate to or load. */
    private static final Set<String> URL_ATTRS = Set.of("href", "src", "action", "formaction", "poster", "cite",
            "background", "xlink:href", "data", "manifest", "icon", "longdesc", "usemap", "codebase", "ping");

    /** Never accepted: inline style (CSP), HTML in an attribute, multi-URL lists we would have to parse, legacy code paths. */
    private static final Set<String> FORBIDDEN_ATTRS = Set.of("style", "srcdoc", "srcset", "imagesrcset", "is",
            "attributename", "dynsrc", "lowsrc", "datasrc", "datafld", "dataformatas", "xmlns:xlink");

    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https", "mailto");

    private Safety() {
    }

    static void checkTag(String tag) {
        if (tag == null || !TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException("invalid element name: " + tag);
        }
        if (FORBIDDEN_TAGS.contains(tag.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("<" + tag + "> cannot be built (it can load or run code)");
        }
    }

    static boolean isVoid(String tag) {
        return VOID.contains(tag);
    }

    static void checkAttr(String name, String value) {
        if (name == null || !ATTR.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid attribute name: " + name);
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("on")) {
            throw new IllegalArgumentException("event handler attributes are not allowed (" + name + "); use an action");
        }
        if (FORBIDDEN_ATTRS.contains(n)) {
            throw new IllegalArgumentException("attribute '" + name + "' is not allowed");
        }
        if (value != null && URL_ATTRS.contains(n) && !isSafeUrl(value)) {
            throw new IllegalArgumentException("unsafe URL in " + name + ": " + value);
        }
    }

    /** Relative URLs, and absolute ones with http, https or mailto. Decides the scheme the way browsers do. */
    static boolean isSafeUrl(String url) {
        StringBuilder s = new StringBuilder(url.length());
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c > 0x20 && c != 0x7f) s.append(c); // browsers drop tabs, newlines and controls inside the scheme
        }
        String t = s.toString();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ':') {
                return SAFE_SCHEMES.contains(t.substring(0, i).toLowerCase(Locale.ROOT));
            }
            if (c == '/' || c == '?' || c == '#') {
                return true; // no scheme: relative
            }
            if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
                return true; // cannot be a scheme, so it is a relative path
            }
        }
        return true;
    }

    static void escapeText(String s, StringBuilder out) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                case '\0' -> out.append('�');
                default -> out.append(c);
            }
        }
    }

    static void escapeAttr(String s, StringBuilder out) {
        escapeText(s, out);
    }
}
