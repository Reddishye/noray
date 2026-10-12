package com.redactado.noray.log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last line of defence against secrets in logs: replaces the value of anything that looks like
 * {@code passphrase=...}, {@code psk: ...} or {@code token ...=...} with {@code <redacted>}. Code should never log a
 * secret in the first place; this catches the mistake before it reaches a log collector. Node ids and blob ids are
 * public and are left alone.
 */
public final class Redactor {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)([\\w.-]*(?:passphrase|password|passwd|secret|psk|private|token|seed|authority\\.key)[\\w.-]*)(\\s*[=:]\\s*)([^\\s,;}\\]\"']+)");

    private Redactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = SECRET.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        do {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + "<redacted>"));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }
}
