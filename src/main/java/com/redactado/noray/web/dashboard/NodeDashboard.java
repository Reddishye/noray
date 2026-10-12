package com.redactado.noray.web.dashboard;

import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeManager;
import com.redactado.noray.filesystem.volume.VolumeState;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.capability.CapabilityRegistry;
import com.redactado.noray.kernel.metrics.Metrics;
import com.redactado.noray.log.CircularLogJournal;
import com.redactado.noray.log.LogEntry;
import com.redactado.noray.log.LogLevel;
import com.redactado.noray.log.Redactor;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.pkg.PackageException;
import com.redactado.noray.pkg.PackageManager;
import com.redactado.noray.web.html.Node;
import com.redactado.noray.web.kit.Charts;
import com.redactado.noray.web.kit.FileBrowser;
import com.redactado.noray.web.kit.Kit;
import com.redactado.noray.web.kit.LogView;
import com.redactado.noray.web.kit.Table;
import com.redactado.noray.web.ui.ActionContext;
import com.redactado.noray.web.ui.Component;
import com.redactado.noray.web.ui.Json;
import com.redactado.noray.web.ui.Page;
import com.redactado.noray.web.ui.Render;
import com.redactado.noray.web.ui.Routes;
import com.redactado.noray.web.ui.Toast;
import com.redactado.noray.web.ui.WebApp;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.redactado.noray.web.html.Html.*;
import static com.redactado.noray.web.kit.Kit.*;

/**
 * The node's own dashboard, built only with the public web API (it is the proof that the API is enough). Every service
 * is looked up at render time, so a page shows "not available" instead of failing when a subsystem is off.
 */
public final class NodeDashboard implements WebApp {

    private final KernelContext ctx;
    private final MetricsHistory history;

    public NodeDashboard(KernelContext ctx, MetricsHistory history) {
        this.ctx = ctx;
        this.history = history;
    }

    @Override
    public String title() {
        return "Node";
    }

    @Override
    public void routes(Routes r) {
        r.nav("Overview", "/").nav("Volumes", "/volumes").nav("Files", "/files").nav("Packages", "/packages")
                .nav("Modules", "/modules").nav("Metrics", "/metrics").nav("Logs", "/logs").nav("Components", "/components");
        r.page("/", c -> Page.of("Overview", this::overview));
        r.page("/volumes", c -> Page.of("Volumes", volumesPage()));
        r.page("/files", c -> Page.of("Files", filesPage()));
        r.page("/packages", c -> Page.of("Packages", new PackagesPage()));
        r.page("/modules", c -> Page.of("Modules", this::modules));
        r.page("/metrics", c -> Page.of("Metrics", metricsPage()));
        r.page("/logs", c -> Page.of("Logs", new LogsPage()));
        r.page("/components", c -> Page.of("Components", new ComponentsPage()));
    }

    private <T> Optional<T> service(Class<T> type) {
        try {
            return ctx.getServiceRegistry().get(type);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ── overview ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private Node overview(Render r) {
        return stack(
                pageHeader("Overview", "Live view of this node. Tiles refresh every 2 seconds."),
                r.live(this::alerts, Duration.ofSeconds(5)),
                r.live(this::tiles, Duration.ofSeconds(2)),
                r.live(this::throughput, Duration.ofSeconds(4)));
    }

    private Node alerts(Render r) {
        List<Node> out = new ArrayList<>();
        long offline = service(VolumeManager.class).map(v -> v.list().stream().filter(x -> x.state() == VolumeState.OFFLINE).count()).orElse(0L);
        if (offline > 0) out.add(alert(Tone.WARN, offline + " volume(s) offline", "Data stays readable while enough copies survive; see Volumes."));
        long alarms = Metrics.global().count("witness.alarms");
        if (alarms > 0) out.add(alert(Tone.ERROR, "Integrity alarm", alarms + " witness alarm(s) raised. Investigate, then run `witness clear`."));
        service(PackageManager.class).map(PackageManager::integrityProblem).ifPresent(problem -> out.add(alert(Tone.ERROR,
                "Package approvals failed their integrity check", problem + ". No package loads until an operator reviews them and runs `pkg reseal`.")));
        long reconciled = Metrics.global().count("fs.quota.reconciled");
        if (reconciled > 0) out.add(alert(Tone.WARN, "Quota counters corrected", reconciled + " drifted counter(s) were fixed by maintenance."));
        return out.isEmpty() ? fragment() : stack(out);
    }

    private Node tiles(Render r) {
        var mem = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = mem.getMax() > 0 ? mem.getMax() : mem.getCommitted();
        Duration up = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
        List<Volume> vols = service(VolumeManager.class).map(VolumeManager::list).orElse(List.of());
        long online = vols.stream().filter(v -> v.state() == VolumeState.ONLINE).count();
        int peers = service(MeshTransport.class).map(m -> m.peers().size()).orElse(0);
        List<Double> in = history.rate("net.bytes.in"), out = history.rate("net.bytes.out");
        return grid(
                stat("Uptime", uptime(up), null, span(ctx.getActiveModuleNames().size() + " modules active")),
                stat("Heap", mem.getUsed() / 1048576, "MiB",
                        meter("Heap in use", mem.getUsed(), max, max * 0.7, max * 0.9),
                        Charts.sparkline("Heap over the last minutes", history.heapMiB())),
                stat("Volumes online", online, "of " + vols.size(), vols.isEmpty() ? badge("none attached", Tone.NEUTRAL)
                        : vols.size() == online ? badge("healthy", Tone.OK) : badge((vols.size() - online) + " not online", Tone.WARN)),
                stat("Peers", peers, null, span(Kit.count(Metrics.global().count("net.handshakes.in")
                        + Metrics.global().count("net.handshakes.out")) + " handshakes")),
                stat("Network in", rate(last(in)), "/s", Charts.sparkline("Inbound traffic", in)),
                stat("Network out", rate(last(out)), "/s", Charts.sparkline("Outbound traffic", out)));
    }

    private Node throughput(Render r) {
        List<String> labels = history.labels();
        List<Double> in = history.rate("net.bytes.in").stream().map(v -> v / 1024).toList();
        List<Double> out = history.rate("net.bytes.out").stream().map(v -> v / 1024).toList();
        List<Double> blobIn = history.rate("blob.bytes.in").stream().map(v -> v / 1024).toList();
        return card("Throughput", labels.size() < 2 ? empty("Collecting samples…")
                : Charts.line("Overlay and blob traffic, KiB/s", labels, List.of(new Charts.Series("Network in", in),
                new Charts.Series("Network out", out), new Charts.Series("Blobs stored for peers", blobIn)), "KiB/s"));
    }

    // ── volumes ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private Component volumesPage() {
        Table<Volume> table = Table.of("Volumes", () -> service(VolumeManager.class).map(VolumeManager::list).orElse(List.of()))
                .column("Id", v -> mono(v.id().toString().substring(0, Math.min(13, v.id().toString().length()))))
                .column("Kind", v -> v.kind().name().toLowerCase(), Comparator.comparing(Volume::kind))
                .column("State", v -> badge(v.state().name().toLowerCase(), toneOf(v.state())), Comparator.comparing(Volume::state))
                .column("Writable", v -> v.writable() ? "yes" : "read-only")
                .column("Driver", v -> v.driver().name(), Comparator.comparing(v -> v.driver().name()))
                .filter((v, q) -> (v.id() + " " + v.kind() + " " + v.state() + " " + v.driver().name()).toLowerCase().contains(q))
                .emptyText("No volumes attached. Use `volume add` in the shell.");
        return r -> stack(pageHeader("Volumes", "Where this node's bytes live."),
                service(VolumeManager.class).isEmpty() ? alert(Tone.INFO, "Filesystem not running", "The volume manager is not available.")
                        : flushCard("Attached volumes", List.of(), r.live(table, Duration.ofSeconds(10))));
    }

    private static Tone toneOf(VolumeState s) {
        return switch (s) {
            case ONLINE -> Tone.OK;
            case DEGRADED, PROBING -> Tone.WARN;
            case OFFLINE -> Tone.ERROR;
            default -> Tone.NEUTRAL;
        };
    }

    // ── files ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    private Component filesPage() {
        Optional<FileSystemService> fs = service(FileSystemService.class);
        FileBrowser browser = fs.map(f -> new FileBrowser(f, VfsPath.root(), "Namespace").allowWrites()).orElse(null);
        return r -> stack(pageHeader("Files", "Browse the namespace and preview files. Nothing is executed: HTML and scripts show as text."),
                browser == null ? alert(Tone.INFO, "Filesystem not running", "The filesystem service is not available.")
                        : r.live(browser));
    }

    // ── packages ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private final class PackagesPage implements Component {
        @Override
        public Node render(Render r) {
            Optional<PackageManager> pm = service(PackageManager.class);
            if (pm.isEmpty()) return stack(pageHeader("Packages", null), alert(Tone.INFO, "Package manager not running", ""));
            PackageManager p = pm.get();
            List<Node> rows = new ArrayList<>();
            for (var i : p.list()) {
                String id = i.id(), v = i.version().toString();
                Node state = i.active() ? badge("active", Tone.OK) : i.approved() ? badge("approved", Tone.INFO) : badge("installed", Tone.NEUTRAL);
                List<Object> actions = new ArrayList<>();
                if (!i.approved()) {
                    actions.add(Kit.button("Approve as requested", Variant.PRIMARY, cls("nr-btn-s"),
                            confirm("Approve " + id + " " + v + " with: " + (i.manifest().requests().isEmpty() ? "nothing"
                                    : String.join(", ", i.manifest().requests())) + "?"),
                            r.on("click", e -> act(e.ctx(), () -> p.approve(id, v, null, true), "Approved " + id + " " + v))));
                } else if (!i.active()) {
                    actions.add(Kit.button("Activate", Variant.DEFAULT, cls("nr-btn-s"),
                            r.on("click", e -> act(e.ctx(), () -> p.activate(id, v), id + " " + v + " is active"))));
                } else {
                    actions.add(Kit.button("Deactivate", Variant.DANGER, cls("nr-btn-s"), confirm("Deactivate " + id + "?"),
                            r.on("click", e -> act(e.ctx(), () -> p.deactivate(id), id + " deactivated"))));
                    actions.add(Kit.button("Roll back", Variant.GHOST, cls("nr-btn-s"), confirm("Return " + id + " to its previous version?"),
                            r.on("click", e -> act(e.ctx(), () -> p.rollback(id), id + " rolled back"))));
                }
                rows.add(tr(td(strong(id), br(), small(cls("nr-muted"), i.manifest().description())), td(mono(v)),
                        td(i.manifest().sandbox().text()), td(i.manifest().publisher()),
                        td(small(i.approved() ? (i.grants().isEmpty() ? "(nothing)" : String.join(", ", i.grants()))
                                : "requests " + (i.manifest().requests().isEmpty() ? "nothing" : String.join(", ", i.manifest().requests())))),
                        td(state), td(row(actions))));
            }
            List<Node> pubs = new ArrayList<>();
            for (var pub : p.publishers().all()) {
                pubs.add(tr(td(strong(pub.name())), td(pub.threshold() + " of " + pub.keys().size()),
                        td(pub.trusted() ? badge("trusted (in-JVM)", Tone.WARN) : badge("process sandbox", Tone.OK)),
                        td(small(mono(String.join(" ", pub.keysHex().stream().map(k -> k.substring(0, 16) + "…").toList()))))));
            }
            long broken = p.audit().verify();
            return stack(pageHeader("Packages", "Signed code from pinned publishers. Installing runs nothing; approving grants exactly what is shown.",
                            broken < 0 ? badge("audit chain intact", Tone.OK) : badge("audit chain broken at line " + broken, Tone.ERROR)),
                    flushCard("Installed", List.of(), rows.isEmpty() ? empty("No packages. Install one with `pkg install FILE`.")
                            : div(cls("nr-table-wrap"), table(cls("nr-table"), caption(cls("nr-visually-hidden"), "Installed packages"),
                            thead(tr(th("Package"), th("Version"), th("Sandbox"), th("Publisher"), th("Grants"), th("State"), th("Actions"))),
                            tbody(rows)))),
                    flushCard("Pinned publishers", List.of(), pubs.isEmpty() ? empty("No publishers pinned (`pkg publisher add`).")
                            : div(cls("nr-table-wrap"), table(cls("nr-table"), caption(cls("nr-visually-hidden"), "Publishers"),
                            thead(tr(th("Name"), th("Threshold"), th("Allowed"), th("Keys"))), tbody(pubs)))),
                    card("Audit trail", LogView.of("Package audit", p.audit().tail(15).stream().map(NodeDashboard::auditLine).toList())));
        }

        private void act(ActionContext c, Runnable op, String done) {
            try {
                op.run();
                c.toast(Toast.Level.SUCCESS, done);
            } catch (PackageException e) {
                c.toast(Toast.Level.ERROR, e.getMessage());
            }
        }
    }

    static LogView.Line auditLine(String json) {
        try {
            var m = Json.parseObject(json);
            return new LogView.Line(Instant.parse(String.valueOf(m.get("ts"))),
                    String.valueOf(m.get("event")).contains("refused") ? "WARN" : "INFO", String.valueOf(m.get("event")),
                    m.get("subject") + " " + m.get("detail"));
        } catch (RuntimeException e) {
            return new LogView.Line(Instant.EPOCH, "WARN", "audit", json);
        }
    }

    // ── modules ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private Node modules(Render r) {
        Optional<CapabilityRegistry> caps = service(CapabilityRegistry.class);
        List<Node> rows = new ArrayList<>();
        for (String id : ctx.getActiveModuleNames().stream().sorted().toList()) {
            var grants = caps.flatMap(c -> c.grantsOf(id));
            rows.add(tr(td(mono(id)), td(grants.isPresent() ? badge("sandboxed", Tone.INFO) : badge("trusted", Tone.NEUTRAL)),
                    td(small(grants.map(g -> g.isEmpty() ? "(nothing)" : g.toString()).orElse("full context")))));
        }
        List<LogView.Line> denials = caps.map(c -> c.recentDenials(50).stream()
                .map(d -> new LogView.Line(Instant.ofEpochMilli(d.timeMillis()), "WARN", d.moduleId(), "denied " + d.capability()))
                .toList()).orElse(List.of());
        return stack(pageHeader("Modules", "What runs in this node and what each sandboxed module may do."),
                flushCard("Active modules", List.of(), div(cls("nr-table-wrap"), table(cls("nr-table"),
                        caption(cls("nr-visually-hidden"), "Active modules"), thead(tr(th("Module"), th("Kind"), th("Grants"))), tbody(rows)))),
                card("Recent capability refusals", LogView.of("Capability refusals", denials)));
    }

    // ── metrics ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private Component metricsPage() {
        Table<Map.Entry<String, Long>> table = Table.of("Metrics", () -> new ArrayList<>(Metrics.global().snapshot().entrySet()))
                .column("Name", e -> mono(e.getKey()), Map.Entry.comparingByKey())
                .numeric("Value", e -> Kit.count(e.getValue()), Map.Entry.comparingByValue())
                .numeric("Exact", e -> e.getValue(), Map.Entry.comparingByValue())
                .filter((e, q) -> e.getKey().contains(q))
                .sortedBy(0, true)
                .pageSize(100);
        return r -> stack(pageHeader("Metrics", "Every counter and gauge, as exported to Prometheus (noray.metrics.port)."),
                flushCard("All metrics", List.of(), r.live(table, Duration.ofSeconds(5))));
    }

    // ── logs ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    private final class LogsPage implements Component {
        private LogLevel min = LogLevel.INFO;

        @Override
        public Node render(Render r) {
            Map<String, String> levels = new LinkedHashMap<>();
            for (LogLevel l : LogLevel.values()) levels.put(l.name(), l.name());
            return stack(pageHeader("Logs", "The node's recent journal (newest last)."),
                    div(cls("nr-form-row"), selectField("level", "Minimum level", levels, min.name(),
                            r.onView("change", e -> min = LogLevel.valueOf(e.value())))),
                    r.live(this::lines, Duration.ofSeconds(3)));
        }

        private Node lines(Render r) {
            Optional<CircularLogJournal> j = service(CircularLogJournal.class);
            if (j.isEmpty()) return alert(Tone.INFO, "No journal", "The log journal is not registered.");
            List<LogEntry> entries = new ArrayList<>(j.get().snapshot(300, min));
            return LogView.of("Node journal", entries.stream().map(e -> new LogView.Line(e.timestamp(), e.level().name(), e.source(),
                    Redactor.redact(e.message()))).toList());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private static double last(List<Double> v) {
        return v.isEmpty() ? 0 : v.getLast();
    }

    private static String rate(double bytesPerSecond) {
        return Kit.bytes((long) bytesPerSecond);
    }

    static String uptime(Duration d) {
        long days = d.toDays(), h = d.toHoursPart(), m = d.toMinutesPart();
        return days > 0 ? days + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : m + "m " + d.toSecondsPart() + "s";
    }
}
