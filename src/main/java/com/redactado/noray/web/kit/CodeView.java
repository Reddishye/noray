package com.redactado.noray.web.kit;

import com.redactado.noray.web.html.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.redactado.noray.web.html.Html.*;

/**
 * Source code with line numbers and light, regex-based highlighting (keywords, strings, numbers, comments, keys).
 * It is deliberately not a parser: wrong colours are harmless, and every token is escaped text.
 */
public final class CodeView {

    private static final int MAX_LINES = 5_000;

    private static final Set<String> KEYWORDS = Set.of("abstract", "async", "await", "boolean", "break", "case", "catch",
            "char", "class", "const", "continue", "def", "default", "do", "double", "elif", "else", "enum", "export",
            "extends", "false", "final", "finally", "float", "fn", "for", "from", "func", "function", "go", "if",
            "implements", "import", "in", "int", "interface", "let", "long", "match", "module", "new", "nil", "None",
            "null", "package", "private", "protected", "pub", "public", "record", "return", "sealed", "self", "static",
            "struct", "super", "switch", "this", "throw", "throws", "trait", "true", "True", "False", "try", "type", "use",
            "val", "var", "void", "while", "with", "yield", "impl", "mut", "where", "lambda", "not", "and", "or", "is");

    private static final Pattern TOKEN = Pattern.compile(
            "(?<com>//.*|#(?![0-9a-fA-F]{3,8}\\b).*|/\\*.*?\\*/|--.*)"
                    + "|(?<str>\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|`[^`]*`)"
                    + "|(?<num>\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)\\b)"
                    + "|(?<word>\\b[A-Za-z_][A-Za-z0-9_]*\\b)");

    private CodeView() {
    }

    /** {@code language} is a hint (file extension or name); unknown languages are shown without colours. */
    public static Node of(String source, String language) {
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        boolean highlight = !lang.isEmpty() && !Set.of("txt", "text", "log", "csv").contains(lang);
        boolean keyValue = Set.of("properties", "conf", "ini", "env", "toml", "yaml", "yml").contains(lang);
        String[] lines = source.split("\n", -1);
        List<Node> rows = new ArrayList<>();
        int n = Math.min(lines.length, MAX_LINES);
        for (int i = 0; i < n; i++) {
            String line = lines[i].replace("\r", "").replace("\t", "    ");
            Object content = highlight ? (keyValue ? keyValueLine(line) : tokens(line)) : line;
            rows.add(tr(td(cls("nr-ln"), String.valueOf(i + 1)), td(content)));
        }
        if (lines.length > MAX_LINES) {
            rows.add(tr(td(cls("nr-ln"), "…"), td(cls("nr-muted"), (lines.length - MAX_LINES) + " more lines not shown")));
        }
        return pre(cls("nr-code"), aria("label", "Source code"), code(table(role("presentation"), tbody(rows))));
    }

    private static List<Node> tokens(String line) {
        List<Node> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(line);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(text(line.substring(last, m.start())));
            String cls;
            if (m.group("com") != null) cls = "tk-com";
            else if (m.group("str") != null) cls = "tk-str";
            else if (m.group("num") != null) cls = "tk-num";
            else cls = KEYWORDS.contains(m.group()) ? "tk-kw" : null;
            out.add(cls == null ? text(m.group()) : span(cls(cls), m.group()));
            last = m.end();
        }
        if (last < line.length()) out.add(text(line.substring(last)));
        return out;
    }

    private static List<Node> keyValueLine(String line) {
        String t = line.stripLeading();
        if (t.startsWith("#") || t.startsWith(";") || t.startsWith("!")) return List.of(span(cls("tk-com"), line));
        int i = indexOfSeparator(line);
        if (i < 0) return List.of(text(line));
        return List.of(span(cls("tk-key"), line.substring(0, i)), text(line.substring(i, i + 1)), text(line.substring(i + 1)));
    }

    private static int indexOfSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '=' || c == ':') return i;
        }
        return -1;
    }
}
