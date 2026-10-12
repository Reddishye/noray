package com.redactado.noray.web.kit;

import com.redactado.noray.web.html.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.redactado.noray.web.html.Html.*;

/**
 * Charts rendered on the server as SVG: no script, styled by theme tokens, readable without colour (titles,
 * descriptions, legends, a data table for screen readers). Series colours come from the theme's categorical palette.
 */
public final class Charts {

    /** A named series of values; {@code null} values are gaps. */
    public record Series(String name, List<Double> values) {
    }

    private static final int W = 640, H = 220, PAD_L = 44, PAD_R = 12, PAD_T = 12, PAD_B = 26;

    private Charts() {
    }

    /** A line chart over shared x labels; the first series also gets a soft area fill. */
    public static Node line(String title, List<String> xLabels, List<Series> series, String unit) {
        double max = niceMax(series.stream().flatMap(s -> s.values().stream()).filter(v -> v != null).mapToDouble(Double::doubleValue).max().orElse(1));
        int n = Math.max(2, xLabels.size());
        List<Node> parts = new ArrayList<>();
        parts.add(svgTitle(title));
        parts.add(desc(describe(series, unit)));
        parts.addAll(gridLines(max, unit));
        for (int s = 0; s < series.size(); s++) {
            List<Double> vals = series.get(s).values();
            StringBuilder d = new StringBuilder();
            StringBuilder area = new StringBuilder();
            boolean pen = false;
            double firstX = -1, lastX = -1;
            for (int i = 0; i < vals.size(); i++) {
                Double v = vals.get(i);
                if (v == null) { pen = false; continue; }
                double x = x(i, n), y = y(v, max);
                d.append(pen ? " L" : " M").append(f(x)).append(',').append(f(y));
                if (firstX < 0) firstX = x;
                lastX = x;
                area.append(area.isEmpty() ? "M" + f(x) + "," + f(H - PAD_B) + " L" : " L").append(f(x)).append(',').append(f(y));
                pen = true;
            }
            if (s == 0 && firstX >= 0) {
                parts.add(path(cls("nr-area", "nr-s1"), attr("d", area + " L" + f(lastX) + "," + f(H - PAD_B) + " Z")));
            }
            parts.add(path(cls("nr-line", "nr-s" + (s % 6 + 1)), attr("d", d.toString().strip())));
        }
        parts.addAll(xAxis(xLabels, n));
        return figure(svg(cls("nr-chart"), attr("viewBox", "0 0 " + W + " " + H), role("img"), aria("label", title), parts),
                legend(series), dataTable(title, xLabels, series));
    }

    /** Vertical bars, one per label (first series only). */
    public static Node bar(String title, List<String> labels, List<Double> values, String unit) {
        double max = niceMax(values.stream().mapToDouble(Double::doubleValue).max().orElse(1));
        List<Node> parts = new ArrayList<>();
        parts.add(svgTitle(title));
        parts.add(desc(describe(List.of(new Series(title, values)), unit)));
        parts.addAll(gridLines(max, unit));
        int n = Math.max(1, values.size());
        double slot = (double) (W - PAD_L - PAD_R) / n;
        double bw = Math.max(2, slot * 0.62);
        for (int i = 0; i < values.size(); i++) {
            double v = values.get(i);
            double x = PAD_L + i * slot + (slot - bw) / 2, y = y(v, max);
            parts.add(rect(cls("nr-s1"), attr("x", f(x)), attr("y", f(y)), attr("width", f(bw)),
                    attr("height", f(Math.max(0, H - PAD_B - y))), attr("rx", 2), svgTitle(labels.get(i) + ": " + fmt(v) + unitSuffix(unit))));
            if (n <= 24) {
                parts.add(svgText(attr("x", f(x + bw / 2)), attr("y", H - 8), attr("text-anchor", "middle"), labels.get(i)));
            }
        }
        return figure(svg(cls("nr-chart"), attr("viewBox", "0 0 " + W + " " + H), role("img"), aria("label", title), parts),
                dataTable(title, labels, List.of(new Series(title, values))));
    }

    /** A tiny trend line for stat tiles. */
    public static Node sparkline(String label, List<Double> values) {
        if (values.size() < 2) return span();
        double max = values.stream().filter(v -> v != null).mapToDouble(Double::doubleValue).max().orElse(1);
        double min = values.stream().filter(v -> v != null).mapToDouble(Double::doubleValue).min().orElse(0);
        double range = max - min == 0 ? 1 : max - min;
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            Double v = values.get(i);
            if (v == null) continue;
            pts.append(f(i * 100.0 / (values.size() - 1))).append(',').append(f(26 - (v - min) / range * 24)).append(' ');
        }
        return svg(cls("nr-chart", "nr-spark"), attr("viewBox", "0 -1 100 28"), attr("preserveAspectRatio", "none"),
                role("img"), aria("label", label), polyline(cls("nr-line", "nr-s1"), attr("points", pts.toString().strip()),
                        attr("vector-effect", "non-scaling-stroke")));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private static List<Node> gridLines(double max, String unit) {
        List<Node> out = new ArrayList<>();
        for (int i = 0; i <= 4; i++) {
            double v = max * i / 4;
            double y = y(v, max);
            out.add(el("line", cls(i == 0 ? "nr-axis" : "nr-grid-line"), attr("x1", PAD_L), attr("x2", W - PAD_R), attr("y1", f(y)), attr("y2", f(y))));
            out.add(svgText(attr("x", PAD_L - 6), attr("y", f(y + 4)), attr("text-anchor", "end"), fmt(v) + (i == 4 ? unitSuffix(unit) : "")));
        }
        return out;
    }

    private static List<Node> xAxis(List<String> labels, int n) {
        List<Node> out = new ArrayList<>();
        int step = Math.max(1, labels.size() / 6);
        for (int i = 0; i < labels.size(); i += step) {
            out.add(svgText(attr("x", f(x(i, n))), attr("y", H - 8), attr("text-anchor", i == 0 ? "start" : "middle"), labels.get(i)));
        }
        return out;
    }

    private static Node legend(List<Series> series) {
        if (series.size() < 2) return null;
        List<Node> items = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            items.add(li(cls("nr-l" + (i % 6 + 1)), el("i", aria("hidden", "true")), span(cls("nr-muted"), series.get(i).name())));
        }
        return ul(cls("nr-legend"), items);
    }

    /** The numbers behind the chart, for screen readers and copy-paste. */
    private static Node dataTable(String title, List<String> labels, List<Series> series) {
        List<Node> head = new ArrayList<>();
        head.add(th(scope("col"), ""));
        for (Series s : series) head.add(th(scope("col"), s.name()));
        List<Node> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            List<Node> cells = new ArrayList<>();
            cells.add(th(scope("row"), labels.get(i)));
            for (Series s : series) cells.add(td(i < s.values().size() && s.values().get(i) != null ? fmt(s.values().get(i)) : "–"));
            rows.add(tr(cells));
        }
        return table(cls("nr-visually-hidden"), caption(title), thead(tr(head)), tbody(rows));
    }

    private static String describe(List<Series> series, String unit) {
        StringBuilder sb = new StringBuilder();
        for (Series s : series) {
            var stats = s.values().stream().filter(v -> v != null).mapToDouble(Double::doubleValue).summaryStatistics();
            if (stats.getCount() == 0) continue;
            sb.append(s.name()).append(": from ").append(fmt(stats.getMin())).append(" to ").append(fmt(stats.getMax()))
                    .append(unitSuffix(unit)).append(". ");
        }
        return sb.toString().strip();
    }

    private static double x(int i, int n) {
        return PAD_L + (double) i / (n - 1) * (W - PAD_L - PAD_R);
    }

    private static double y(double v, double max) {
        return H - PAD_B - (max == 0 ? 0 : v / max) * (H - PAD_T - PAD_B);
    }

    /** 1, 2, 2.5 or 5 times a power of ten, at or above {@code v}. */
    static double niceMax(double v) {
        if (v <= 0) return 1;
        double p = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[]{1, 2, 2.5, 5, 10}) {
            if (m * p >= v) return m * p;
        }
        return 10 * p;
    }

    static String fmt(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) return String.format(Locale.ROOT, "%.1fG", v / 1e9);
        if (a >= 1e6) return String.format(Locale.ROOT, "%.1fM", v / 1e6);
        if (a >= 1e4) return String.format(Locale.ROOT, "%.0fk", v / 1e3);
        if (a >= 100 || v == Math.rint(v)) return String.format(Locale.ROOT, "%.0f", v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String unitSuffix(String unit) {
        return unit == null || unit.isEmpty() ? "" : " " + unit;
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
