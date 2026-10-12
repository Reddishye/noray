package com.redactado.noray.web.html;

/**
 * An attribute with a validated name and an escaped, always-quoted value. A {@code null} value renders as a boolean
 * attribute ({@code disabled}). URL-valued attributes only accept relative, http(s) and mailto URLs.
 */
public record Attr(String name, String value) {

    public Attr {
        Safety.checkAttr(name, value);
    }

    void render(StringBuilder out) {
        out.append(' ').append(name);
        if (value != null) {
            out.append("=\"");
            Safety.escapeAttr(value, out);
            out.append('"');
        }
    }
}
