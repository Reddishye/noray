package com.redactado.noray.web.kit;

import com.redactado.noray.web.html.Node;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.redactado.noray.web.html.Html.*;

/** Log lines: time, level (coloured and written), message. */
public final class LogView {

    public record Line(Instant time, String level, String source, String message) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private LogView() {
    }

    public static Node of(String label, List<Line> lines) {
        List<Node> rows = new ArrayList<>();
        for (Line l : lines) {
            rows.add(div(cls("nr-log-row"), span(cls("nr-muted"), TIME.format(l.time())),
                    span(cls("nr-lv-" + l.level()), l.level()),
                    span(l.source() == null || l.source().isEmpty() ? null : span(cls("nr-muted"), "[" + l.source() + "] "), l.message())));
        }
        return div(cls("nr-log"), role("log"), aria("label", label), tabindex(0), rows.isEmpty() ? text("(no entries)") : fragment(rows));
    }
}
