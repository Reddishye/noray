package com.redactado.noray.web;

import com.redactado.noray.web.html.Node;
import com.redactado.noray.web.ui.Blob;
import com.redactado.noray.web.ui.Component;
import com.redactado.noray.web.ui.FormData;
import com.redactado.noray.web.ui.Page;
import com.redactado.noray.web.ui.PageView;
import com.redactado.noray.web.ui.Render;
import com.redactado.noray.web.ui.RequestContext;
import com.redactado.noray.web.ui.Toast;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.redactado.noray.web.html.Html.*;
import static org.junit.jupiter.api.Assertions.*;

/** The page engine without HTTP: regions, actions, stale tokens, timed pushes. */
class PageViewTest {

    static PageView.Environment env(boolean interactive) {
        RequestContext rc = new RequestContext() {
            public String param(String n) { return ""; }
            public Optional<String> query(String n) { return Optional.empty(); }
            public Map<String, String> params() { return Map.of(); }
            public String path() { return "/apps/t"; }
            public Optional<String> user() { return Optional.of("operator"); }
            public String link(String p) { return "/apps/t" + p; }
        };
        return new PageView.Environment() {
            public String link(String p) { return rc.link(p); }
            public String blob(Blob b) { return "/_nr/blob/x"; }
            public RequestContext request() { return rc; }
            public String formEndpoint() { return "/_nr/form?t="; }
            public boolean interactive() { return interactive; }
        };
    }

    static List<String> tokens(String html) {
        Matcher m = Pattern.compile("data-nr-on=\"(?:click|submit|change|input):([A-Za-z0-9_-]+)").matcher(html);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static final class Counter implements Component {
        int n;
        public Node render(Render r) {
            return div(span("count " + n), button(r.onClick(() -> n++), "+"));
        }
    }

    @Test
    void anActionReRendersOnlyItsRegionAndStaleTokensDoNothing() {
        Counter counter = new Counter();
        int[] outerRenders = {0};
        PageView pv = new PageView(Page.of("t", r -> {
            outerRenders[0]++;
            return div(h1("title"), r.live(counter));
        }), env(true));
        String html = pv.renderBody().html();
        assertTrue(html.contains("count 0"));
        String token = tokens(html).getFirst();

        PageView.Result res = pv.dispatch(token, "", FormData.EMPTY);
        assertEquals(1, res.patches().size());
        assertTrue(res.patches().getFirst().html().contains("count 1"));
        assertNotEquals("r0", res.patches().getFirst().region(), "only the counter's region");
        assertEquals(1, outerRenders[0], "the page body was not rendered again");

        // the old token was replaced by a fresh one: replaying it does nothing
        assertTrue(pv.dispatch(token, "", FormData.EMPTY).patches().isEmpty());
        assertEquals(1, counter.n);
        String fresh = tokens(res.patches().getFirst().html()).getFirst();
        pv.dispatch(fresh, "", FormData.EMPTY);
        assertEquals(2, counter.n);
        assertTrue(pv.dispatch("forged-token", "", FormData.EMPTY).patches().isEmpty());
    }

    @Test
    void actionsCanRefreshThePageNavigateAndToast() {
        int[] renders = {0};
        PageView pv = new PageView(Page.of("t", r -> {
            renders[0]++;
            return div(button(r.on("click", e -> { e.ctx().refreshPage(); e.ctx().toast(Toast.Level.SUCCESS, "done"); }), "a"),
                    button(r.on("click", e -> e.ctx().navigate("/apps/t/next")), "b"),
                    button(r.on("click", e -> e.ctx().navigate("javascript:alert(1)")), "c"));
        }), env(true));
        List<String> t = tokens(pv.renderBody().html());
        PageView.Result a = pv.dispatch(t.get(0), "", FormData.EMPTY);
        assertEquals("r0", a.patches().getFirst().region());
        assertEquals("done", a.toasts().getFirst().message());
        assertEquals(2, renders[0]);
        List<String> t2 = tokens(a.patches().getFirst().html());
        assertEquals("/apps/t/next", pv.dispatch(t2.get(1), "", FormData.EMPTY).navigate());
        PageView.Result bad = pv.dispatch(t2.get(2), "", FormData.EMPTY);
        assertNull(bad.navigate(), "unsafe navigation refused");
        assertEquals(Toast.Level.ERROR, bad.toasts().getFirst().level());
    }

    @Test
    void formsCarryTheirFieldsAndValuesReachChangeHandlers() {
        String[] got = new String[2];
        PageView pv = new PageView(Page.of("t", r -> div(
                form(r.onSubmit(e -> got[0] = e.form().get("name", "?")), input(name("name"))),
                select(r.on("change", e -> got[1] = e.value()), option(value("a"), "A")))), env(true));
        String html = pv.renderBody().html();
        assertTrue(html.contains("method=\"post\" action=\"/_nr/form?t="), "works without JavaScript");
        List<String> t = tokens(html);
        PageView.Result after = pv.dispatch(t.get(0), "", new FormData(Map.of("name", List.of("Ada"))));
        // the submit re-rendered the body, so the select's old token is stale; use the fresh one
        assertTrue(pv.dispatch(t.get(1), "a", FormData.EMPTY).patches().isEmpty());
        pv.dispatch(tokens(after.patches().getFirst().html()).get(1), "a", FormData.EMPTY);
        assertArrayEquals(new String[]{"Ada", "a"}, got);
    }

    @Test
    void timedRegionsAreDueOnlyAfterTheirInterval() {
        int[] ticks = {0};
        PageView pv = new PageView(Page.of("t", r -> div(r.live(rr -> span("tick " + ticks[0]++), Duration.ofSeconds(2)))), env(true));
        pv.renderBody();
        assertTrue(pv.hasTimedRegions());
        assertTrue(pv.due(Instant.now()).isEmpty());
        List<PageView.Patch> p = pv.due(Instant.now().plusSeconds(3));
        assertEquals(1, p.size());
        assertTrue(p.getFirst().html().contains("tick 1"));
    }

    @Test
    void aFailingComponentOnlyBreaksItsOwnRegion() {
        PageView pv = new PageView(Page.of("t", r -> div(span("fine"), r.live(rr -> { throw new IllegalStateException("boom"); }))), env(true));
        String html = pv.renderBody().html();
        assertTrue(html.contains("fine"));
        assertTrue(html.contains("failed to render") && html.contains("boom"));
    }

    @Test
    void publicPagesCannotBindActions() {
        PageView pv = new PageView(Page.of("t", r -> button(r.onClick(() -> { }), "x")), env(false));
        assertTrue(pv.renderBody().html().contains("failed to render"), "the action was refused at render time");
    }
}
