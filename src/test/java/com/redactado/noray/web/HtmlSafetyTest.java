package com.redactado.noray.web;

import com.redactado.noray.web.html.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.redactado.noray.web.html.Html.*;
import static org.junit.jupiter.api.Assertions.*;

/** Whatever module code passes in, the output contains no markup it did not build through the DSL. */
class HtmlSafetyTest {

    private static final List<String> PAYLOADS = List.of(
            "<script>alert(1)</script>", "\"><img src=x onerror=alert(1)>", "'><svg onload=alert(1)>",
            "</textarea><script>x</script>", "javascript:alert(1)", "<!--", "]]>", "&lt;script&gt;", "\u0000<script>");

    @Test
    void textAndAttributeValuesAreEscaped() {
        for (String evil : PAYLOADS) {
            String html = div(title(evil), data("x", evil), evil, span(evil)).html();
            assertFalse(html.contains("<script"), html);
            assertFalse(html.contains("<img"), html);
            assertFalse(html.contains("<svg"), html);
            assertFalse(html.contains("\"><"), html);
            assertEquals(4, html.split("<").length - 1, "only the elements we built: " + html); // div, span, /span, /div
        }
        assertEquals("<p>a &amp; b &lt; c &gt; d &quot;e&quot; &#39;f&#39;</p>", p("a & b < c > d \"e\" 'f'").html());
    }

    @Test
    void dangerousUrlsAreRefusedAndSafeOnesKept() {
        for (String url : List.of("javascript:alert(1)", "JaVaScRiPt:alert(1)", " javascript:x", "java\tscript:x",
                "java\nscript:x", "\u0001javascript:x", "data:text/html,<script>", "vbscript:x", "jar:file:x")) {
            assertThrows(IllegalArgumentException.class, () -> a(href(url)), url);
            assertThrows(IllegalArgumentException.class, () -> img(src(url)), url);
            assertThrows(IllegalArgumentException.class, () -> form(action(url)), url);
        }
        for (String url : List.of("/apps/x", "files?p=a:b", "#top", "https://example.org/x", "mailto:a@b.c", "a/b:c",
                "javascript&#58;x", "?q=javascript:x")) {
            assertDoesNotThrow(() -> a(href(url)), url);
        }
    }

    @Test
    void scriptCapableElementsAndAttributesCannotBeBuilt() {
        for (String tag : List.of("script", "SCRIPT", "style", "iframe", "object", "embed", "base", "link", "meta",
                "foreignObject", "animate", "set", "template", "math", "bad tag", "a>b", "")) {
            assertThrows(IllegalArgumentException.class, () -> el(tag), tag);
        }
        for (String a : List.of("onclick", "ONLOAD", "onmouseover", "style", "srcdoc", "srcset", "a b", "x=y", "\"x")) {
            assertThrows(IllegalArgumentException.class, () -> div(attr(a, "v")), a);
        }
        assertThrows(IllegalArgumentException.class, () -> el("br", "child"), "void elements have no children");
        assertThrows(IllegalArgumentException.class, () -> div(new Object()), "unknown part types are an error");
    }

    @Test
    void partsComposeNaturally() {
        Node n = ul(cls("list"), cls(null, "dense"), List.of(li("a"), li(1)), null, when(false, li("hidden")),
                new Object[]{li(true)}, Optional.of(li('c')));
        assertEquals("<ul class=\"list dense\"><li>a</li><li>1</li><li>true</li><li>c</li></ul>", n.html());
        assertEquals("<input type=\"checkbox\" checked>", input(type("checkbox"), checked()).html());
        assertEquals("ab", fragment("a", null, "b").html());
    }
}
