package com.redactado.noray.web;

import com.redactado.noray.kernel.api.*;
import com.redactado.noray.kernel.capability.CapabilitySet;
import com.redactado.noray.kernel.core.DefaultServiceRegistry;
import com.redactado.noray.log.CircularLogJournal;
import com.redactado.noray.log.LogLevel;
import com.redactado.noray.web.dashboard.MetricsHistory;
import com.redactado.noray.web.dashboard.NodeDashboard;
import com.redactado.noray.web.kit.Charts;
import com.redactado.noray.web.kit.FilePreview;
import com.redactado.noray.web.kit.Markdown;
import com.redactado.noray.web.server.Mounts;
import com.redactado.noray.web.ui.MediaTypes;
import com.redactado.noray.web.ui.Page;
import com.redactado.noray.web.ui.PageView;
import com.redactado.noray.web.ui.RequestContext;
import com.redactado.noray.web.ui.Router;
import com.redactado.noray.web.ui.Routes;
import com.redactado.noray.web.ui.WebApp;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/** Kit components render safely, and every dashboard page renders even with no subsystem running. */
class KitAndDashboardTest {

    @Test
    void markdownIsSafeByConstruction() {
        String html = Markdown.render("""
                # Title <script>alert(1)</script>
                Some **bold** and `code` and [ok](https://example.org) and [bad](javascript:alert(1)).
                <img src=x onerror=alert(1)>
                | a | b |
                |---|---|
                | 1 | <b>2</b> |
                """).html();
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("<img"), html);
        assertFalse(html.contains("href=\"javascript"), html);
        assertTrue(html.contains("<h2>Title &lt;script&gt;"), "the page owns <h1>; HTML is text");
        assertTrue(html.contains("<strong>bold</strong>") && html.contains("<code>code</code>"));
        assertTrue(html.contains("rel=\"noopener noreferrer\""));
        assertTrue(html.contains("<span>bad (javascript:alert(1"), "an unsafe link is shown as text: " + html);
        assertTrue(html.contains("<td>&lt;b&gt;2&lt;/b&gt;</td>"));
    }

    @Test
    void mediaTypesComeFromContentNotNames() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        assertEquals(MediaTypes.Kind.IMAGE, MediaTypes.detect("notes.txt", png).kind(), "a PNG named .txt is a PNG");
        assertEquals(MediaTypes.Kind.TEXT, MediaTypes.detect("x.png", "hello".getBytes()).kind(), "text named .png is text");
        assertEquals(MediaTypes.Kind.TEXT, MediaTypes.detect("a.svg", "<svg onload=x>".getBytes()).kind(), "SVG is source text");
        assertEquals(MediaTypes.Kind.BINARY, MediaTypes.detect("a.bin", new byte[]{0, 1, 2}).kind());
        assertEquals(MediaTypes.Kind.PDF, MediaTypes.detect("a", "%PDF-1.7".getBytes()).kind());
        assertEquals("java", MediaTypes.detect("A.java", "class A {}".getBytes()).language());
        byte[] cut = "é".getBytes(StandardCharsets.UTF_8);
        assertEquals(MediaTypes.Kind.TEXT, MediaTypes.detect("t.txt", new byte[]{'a', cut[0]}).kind(), "cut multi-byte at the end");
    }

    @Test
    void previewsNeverRenderActiveContent() {
        PageView pv = new PageView(Page.of("p", r -> FilePreview.render(r, new FilePreview.FileInfo("page.html", "/page.html",
                () -> "<html><script>alert(1)</script></html>".getBytes()))), PageViewTest.env(true));
        String html = pv.renderBody().html();
        assertFalse(html.contains("<script"), html);
        assertTrue(html.contains("&lt;script&gt;"), "shown as source");
        PageView csv = new PageView(Page.of("p", r -> FilePreview.render(r, new FilePreview.FileInfo("d.csv", "/d.csv",
                () -> "name,qty\n\"a, b\",2\n".getBytes()))), PageViewTest.env(true));
        assertTrue(csv.renderBody().html().contains("<td>a, b</td>"));
    }

    @Test
    void chartsCarryTextAlternatives() {
        String svg = Charts.line("Traffic", List.of("a", "b", "c"),
                List.of(new Charts.Series("in", List.of(1.0, 5.0, 3.0)), new Charts.Series("out", Arrays.asList(2.0, null, 4.0))), "KiB/s").html();
        assertTrue(svg.contains("<title>Traffic</title>") && svg.contains("<desc>in: from 1 to 5 KiB/s."));
        assertTrue(svg.contains("<table class=\"nr-visually-hidden\">"), "data table for screen readers");
        assertTrue(svg.contains("<td>–</td>"), "gaps are explicit");
    }

    @Test
    void sandboxedModulesOnlyMountUnderTheirOwnNameAndGrantedPrefixes() {
        Mounts mounts = new Mounts();
        WebApp app = new WebApp() {
            public String title() { return "x"; }
            public void routes(Routes r) { }
        };
        WebApps none = mounts.bound("com.example.mod", false, List.of());
        assertThrows(SecurityException.class, () -> none.mountApp("anything", app));
        WebApps bound = mounts.bound("com.example.mod", true, List.of("/blog"));
        assertEquals("/apps/com.example.mod", bound.mountApp("node", app).path(), "the slug asked for is ignored");
        assertThrows(SecurityException.class, () -> bound.mountSite("/", (p, r) -> Optional.empty()));
        assertThrows(SecurityException.class, () -> bound.mountSite("/blogger", (p, r) -> Optional.empty()));
        assertEquals("/blog/2026", bound.mountSite("/blog/2026", (p, r) -> Optional.empty()).path());
        assertThrows(IllegalArgumentException.class, () -> mounts.mountSite("/_nr/x", (p, r) -> Optional.empty()));
        assertTrue(CapabilitySet.parse("web.site:/blog").allows("web.site", "/blog/2026"));
        assertFalse(CapabilitySet.parse("web.site:/blog").allows("web.site", "/blogger"));
        assertFalse(CapabilitySet.parse("service:*").allows("service", "WebApps"), "the unscoped service needs an exact grant");
    }

    @Test
    void everyDashboardPageRendersWithAndWithoutServices() {
        for (boolean withServices : new boolean[]{false, true}) {
            DefaultServiceRegistry services = new DefaultServiceRegistry();
            if (withServices) {
                CircularLogJournal j = new CircularLogJournal(100);
                j.append(LogLevel.WARN, "test", "something happened noray.net.mesh.psk=abcdef");
                services.register(CircularLogJournal.class, j);
            }
            KernelContext ctx = new KernelContext() {
                public KernelPhase getPhase() { return KernelPhase.RUNNING; }
                public ServiceRegistry getServiceRegistry() { return services; }
                public EventBus getEventBus() { return null; }
                public ExecutorService getExecutionEngine() { return null; }
                public Configuration getConfiguration() { return null; }
                public void registerResource(String m, AutoCloseable r) { }
                public List<KernelModule> getActiveModules() { return List.of(); }
                public Set<String> getActiveModuleNames() { return Set.of("fs", "net", "web"); }
            };
            try (MetricsHistory history = new MetricsHistory()) {
                Router router = new Router();
                new NodeDashboard(ctx, history).routes(router);
                for (Router.NavEntry e : router.navEntries()) {
                    var match = router.match(e.appPath()).orElseThrow();
                    Page page = ((Router.PageTarget) match.target()).handler().apply(stubRequest(e.appPath()));
                    String html = new PageView(page, PageViewTest.env(true)).renderBody().html();
                    assertFalse(html.contains("failed to render"), e.label() + ": " + html);
                    if (withServices && e.label().equals("Logs")) {
                        assertTrue(html.contains("something happened"));
                        assertFalse(html.contains("abcdef"), "secrets in log lines are redacted on the way out");
                    }
                }
            }
        }
    }

    private static RequestContext stubRequest(String path) {
        return new RequestContext() {
            public String param(String n) { return ""; }
            public Optional<String> query(String n) { return Optional.empty(); }
            public Map<String, String> params() { return Map.of(); }
            public String path() { return path; }
            public Optional<String> user() { return Optional.of("operator"); }
            public String link(String p) { return "/apps/node" + p; }
        };
    }
}
