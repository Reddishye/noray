package com.redactado.noray.web.dashboard;

import com.redactado.noray.web.html.Node;
import com.redactado.noray.web.kit.Charts;
import com.redactado.noray.web.kit.CodeView;
import com.redactado.noray.web.kit.Kit;
import com.redactado.noray.web.kit.Markdown;
import com.redactado.noray.web.kit.Table;
import com.redactado.noray.web.kit.Tabs;
import com.redactado.noray.web.ui.Blob;
import com.redactado.noray.web.ui.Component;
import com.redactado.noray.web.ui.Render;
import com.redactado.noray.web.ui.Toast;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.redactado.noray.web.html.Html.*;
import static com.redactado.noray.web.kit.Kit.*;

/** A living style guide: every kit component, rendered with the current theme, for module authors to copy from. */
final class ComponentsPage implements Component {

    record Planet(String name, double au, int moons) {
    }

    private static final String[] CAPTIONS = {"Graphite", "Cobalt", "Iris", "Moss", "Amber", "Signal"};
    private final Table<Planet> table = Table.of("Planets", () -> List.of(new Planet("Mercury", 0.39, 0),
                    new Planet("Venus", 0.72, 0), new Planet("Earth", 1, 1), new Planet("Mars", 1.52, 2),
                    new Planet("Jupiter", 5.2, 95), new Planet("Saturn", 9.54, 146), new Planet("Uranus", 19.2, 28),
                    new Planet("Neptune", 30.06, 16)))
            .column("Planet", Planet::name, Comparator.comparing(Planet::name))
            .numeric("Distance (AU)", Planet::au, Comparator.comparingDouble(Planet::au))
            .numeric("Moons", Planet::moons, Comparator.comparingInt(Planet::moons))
            .filter((p, q) -> p.name().toLowerCase().contains(q))
            .pageSize(5);
    private final Tabs tabs = new Tabs()
            .add("Markdown", r -> Markdown.render("""
                    ## Written in Markdown
                    Rendered to **nodes**, never to raw HTML: `<script>` stays text and [links](https://example.org) open safely.
                    > Square corners, hairlines, no shadows.
                    """))
            .add("Code", r -> CodeView.of("""
                    public final class Hello implements Component {
                        public Node render(Render r) {
                            return card("Hello", p("Built in Java, styled by tokens."));
                        }
                    }""", "java"))
            .add("Tree", r -> tree(List.of(new TreeItem("mnt", List.of(new TreeItem("sites", List.of(TreeItem.leaf("index.html"),
                    TreeItem.leaf("style.css"))), TreeItem.leaf("notes.md"))), TreeItem.leaf("repo")), 2));
    private int clicks;

    @Override
    public Node render(Render r) {
        List<Node> tiles = new ArrayList<>();
        List<byte[]> images = SampleImages.all();
        for (int i = 0; i < images.size(); i++) {
            byte[] png = images.get(i);
            String name = CAPTIONS[i].toLowerCase() + ".png";
            tiles.add(media(r.blob(new Blob(name, "image/png", () -> png)), CAPTIONS[i] + " abstract gradient", CAPTIONS[i]));
        }
        Map<String, Object> kv = new LinkedHashMap<>();
        kv.put("Theme", "noir (noray.web.theme)");
        kv.put("Corners", "square (radius tokens are 0)");
        kv.put("Fonts", mono("system stacks; nothing is downloaded"));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        return stack(
                pageHeader("Components", "Every kit component in the current theme. Hover the images.",
                        dialogButton("Open a dialog", "demo-dialog", Variant.PRIMARY),
                        menu("Actions", List.of(new MenuItem("Say hello", r.onClick(() -> clicks++)), new MenuItem("Reset", r.onClick(() -> clicks = 0))))),
                grid(stat("Clicks", clicks, null, span("from the Actions menu")),
                        stat("Throughput", "42.0", "MiB/s", Charts.sparkline("trend", List.of(3.0, 5.0, 4.0, 8.0, 6.0, 9.0, 7.0, 11.0))),
                        stat("Volumes", 8, "of 8", badge("healthy", Tone.OK)),
                        stat("Heap", "512", "MiB", meter("Heap", 512, 1024, 700, 900))),
                gallery(tiles),
                row(badge("neutral", Tone.NEUTRAL), badge("ok", Tone.OK), badge("warn", Tone.WARN), badge("error", Tone.ERROR),
                        badge("info", Tone.INFO)),
                row(Kit.button("Primary", Variant.PRIMARY, r.on("click", e -> e.ctx().toast(Toast.Level.SUCCESS, "Primary pressed"))),
                        Kit.button("Default", Variant.DEFAULT, r.on("click", e -> e.ctx().toast(Toast.Level.INFO, "Default pressed"))),
                        Kit.button("Danger", Variant.DANGER, confirm("Really?"), r.on("click", e -> e.ctx().toast(Toast.Level.ERROR, "Danger pressed"))),
                        Kit.button("Ghost", Variant.GHOST)),
                alert(Tone.WARN, "Heads up", "Alerts carry a hairline and a status edge; the text always says what the colour means."),
                cardWithActions("Card with actions", List.of(Kit.button("Edit", Variant.GHOST, cls("nr-btn-s"))), keyValues(kv)),
                flushCard("Table", List.of(), r.live(table)),
                card("Chart", Charts.line("Requests per minute", List.of("10:00", "10:05", "10:10", "10:15", "10:20", "10:25"),
                        List.of(new Charts.Series("api", List.of(12.0, 18.0, 15.0, 26.0, 22.0, 31.0)),
                                new Charts.Series("static", List.of(8.0, 9.0, 14.0, 11.0, 16.0, 13.0))), "req/min")),
                card("Tabs", r.live(tabs)),
                card("Timeline", timeline(List.of(new TimelineEntry(now, "Package approved", "org.noray.examples.hosting 1.0.0"),
                        new TimelineEntry(now.minus(2, ChronoUnit.HOURS), "Volume attached", mono("kind=memory")),
                        new TimelineEntry(now.minus(1, ChronoUnit.DAYS), "Node started", null)))),
                card("Form", form(r.onSubmit(e -> e.ctx().toast(Toast.Level.SUCCESS, "Hello, " + e.form().get("name", "?"))),
                        div(cls("nr-form-row"), textField("name", "Name", ""), selectField("role", "Role", Map.of("op", "Operator", "ro", "Viewer"), "op")),
                        checkbox("notify", "Notify me", true), br(), br(), submit("Submit", Variant.PRIMARY))),
                dialog("demo-dialog", "A native dialog", List.of(p("Opened by a runtime behaviour, no server round trip. Esc closes it.")),
                        List.of(dialogClose("Close"))));
    }
}
