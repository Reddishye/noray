package com.redactado.noray.web;

import com.redactado.noray.web.html.Node;
import com.redactado.noray.web.server.AccessTokens;
import com.redactado.noray.web.server.Mounts;
import com.redactado.noray.web.server.WebServer;
import com.redactado.noray.web.ui.Blob;
import com.redactado.noray.web.ui.Component;
import com.redactado.noray.web.ui.Page;
import com.redactado.noray.web.ui.Render;
import com.redactado.noray.web.ui.Routes;
import com.redactado.noray.web.ui.Theme;
import com.redactado.noray.web.ui.Upload;
import com.redactado.noray.web.ui.WebApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.redactado.noray.web.html.Html.*;
import static org.junit.jupiter.api.Assertions.*;

/** The real server on a loopback port: login, sessions, CSRF and origin checks, headers, events, blobs, public sites. */
class WebServerTest {

    @TempDir
    Path tmp;

    private WebServer server;
    private final List<Upload> uploaded = new CopyOnWriteArrayList<>();
    private AccessTokens tokens;
    private String token;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};

    static final class Counter implements Component {
        int n;

        @Override
        public Node render(Render r) {
            return div(span(id("count"), "count " + n), button(r.onClick(() -> n++), "+"),
                    img(alt("png"), src(r.blob(new Blob("a.png", "image/png", () -> PNG)))),
                    a(id("html"), href(r.blob(new Blob("evil.html", "text/html", () -> "<script>x</script>".getBytes()))), "html"));
        }
    }

    @BeforeEach
    void start() throws Exception {
        Mounts mounts = new Mounts();
        mounts.mountApp("t", new WebApp() {
            @Override public String title() { return "Test"; }
            @Override public void routes(Routes r) {
                r.nav("Home", "/");
                r.page("/", c -> Page.of("Counter", new Counter()));
                r.page("/upload", c -> Page.of("Upload", rr -> form(rr.onUpload(e -> uploaded.addAll(e.form().files())),
                        input(type("file"), name("f")), button(type("submit"), "Send"))));
            }
        });
        mounts.mountSite("/site", (path, req) -> path.equals("/")
                ? Optional.of(new Site.FileContent("index.html", "<h1>public</h1><script>alert(1)</script>".getBytes()))
                : Optional.empty());
        tokens = new AccessTokens(tmp.resolve("tokens"));
        token = tokens.issue("test");
        WebServer.Config cfg = new WebServer.Config("127.0.0.1", 0, null, new char[0], false,
                Set.of("127.0.0.1", "localhost"), Duration.ofMinutes(30), Duration.ofHours(12), "Noray", 4096, "127.0.0.1", 0);
        server = new WebServer(cfg, mounts, tokens, Theme.harbour());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String base() {
        return "http://127.0.0.1:" + server.port();
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String cookie, String type, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", type);
        if (cookie != null) b.header("Cookie", cookie);
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login() throws Exception {
        return login(token);
    }

    private String login(String tok) throws Exception {
        HttpResponse<String> r = post("/_nr/login?next=/apps/t", null, "application/x-www-form-urlencoded",
                "token=" + tok, "Origin", base());
        assertEquals(303, r.statusCode(), r.body());
        String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict"), setCookie);
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static String find(String html, String regex) {
        Matcher m = Pattern.compile(regex).matcher(html);
        assertTrue(m.find(), regex + " in " + html);
        return m.group(1);
    }

    @Test
    void pagesNeedASignInAndLoginRejectsBadTokensWithBackOff() throws Exception {
        HttpResponse<String> r = get("/apps/t", null);
        assertEquals(303, r.statusCode());
        assertTrue(r.headers().firstValue("Location").orElseThrow().startsWith("/_nr/login?next="));
        assertEquals(200, get("/_nr/login", null).statusCode());
        HttpResponse<String> bad = post("/_nr/login", null, "application/x-www-form-urlencoded", "token=nope", "Origin", base());
        assertEquals(403, bad.statusCode());
        HttpResponse<String> again = post("/_nr/login", null, "application/x-www-form-urlencoded", "token=" + token, "Origin", base());
        assertEquals(429, again.statusCode(), "even the right token waits out the back-off");
        // a cross-site login form is refused before any token is checked
        assertEquals(403, post("/_nr/login", null, "application/x-www-form-urlencoded", "token=x",
                "Sec-Fetch-Site", "cross-site").statusCode());
    }

    @Test
    void aPageCarriesStrictHeadersAndEventsNeedCsrfAndSameOrigin() throws Exception {
        String cookie = login();
        HttpResponse<String> page = get("/apps/t", cookie);
        assertEquals(200, page.statusCode());
        String csp = page.headers().firstValue("Content-Security-Policy").orElseThrow();
        String nonce = find(csp, "'nonce-([A-Za-z0-9_-]+)'");
        assertTrue(csp.contains("default-src 'none'") && csp.contains("frame-ancestors 'none'"), csp);
        assertTrue(page.body().contains("<script nonce=\"" + nonce + "\""), "the only script carries the nonce");
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-referrer", page.headers().firstValue("Referrer-Policy").orElseThrow());

        String pageId = find(page.body(), "data-nr-page=\"([^\"]+)\"");
        String csrf = find(page.body(), "data-nr-csrf=\"([^\"]+)\"");
        String action = find(page.body(), "data-nr-on=\"click:([^\"]+)\"");
        String event = "{\"v\":1,\"page\":\"" + pageId + "\",\"token\":\"" + action + "\",\"type\":\"click\",\"value\":\"\"}";

        assertEquals(403, post("/_nr/event", cookie, "application/json", event, "Sec-Fetch-Site", "same-origin").statusCode(), "no CSRF header");
        assertEquals(403, post("/_nr/event", cookie, "application/json", event, "X-NR-CSRF", csrf,
                "Sec-Fetch-Site", "cross-site").statusCode(), "cross-site");
        assertEquals(401, post("/_nr/event", null, "application/json", event, "X-NR-CSRF", csrf,
                "Sec-Fetch-Site", "same-origin").statusCode(), "no session");
        HttpResponse<String> ok = post("/_nr/event", cookie, "application/json", event, "X-NR-CSRF", csrf, "Sec-Fetch-Site", "same-origin");
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("count 1"), ok.body());
        assertTrue(ok.body().startsWith("{\"v\":1,\"patches\":[{\"region\":"), ok.body());
        assertFalse(ok.body().contains("<script"), "patch HTML is JSON-escaped");
        // a replayed token does nothing
        HttpResponse<String> replay = post("/_nr/event", cookie, "application/json", event, "X-NR-CSRF", csrf, "Sec-Fetch-Site", "same-origin");
        assertTrue(replay.body().contains("\"patches\":[]"), replay.body());
    }

    @Test
    void blobsAreSniffedAndOnlyMediaIsServedInline() throws Exception {
        String cookie = login();
        String body = get("/apps/t", cookie).body();
        String png = find(body, "src=\"(/_nr/blob/[^\"]+)\"");
        String html = find(body, "id=\"html\" href=\"(/_nr/blob/[^\"]+)\"");
        HttpResponse<String> p = get(png, cookie);
        assertEquals("image/png", p.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(p.headers().firstValue("Content-Security-Policy").orElseThrow().contains("sandbox"));
        HttpResponse<String> h = get(html, cookie);
        assertEquals("application/octet-stream", h.headers().firstValue("Content-Type").orElseThrow(), "HTML is never served as HTML");
        assertTrue(h.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment"));
        assertEquals(401, get(png, null).statusCode(), "blob URLs belong to the session");
    }

    @Test
    void publicSitesNeedNoLoginButRunInAnOpaqueOrigin() throws Exception {
        HttpResponse<String> r = get("/site/", null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("<h1>public</h1>"));
        String csp = r.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertTrue(csp.startsWith("sandbox allow-scripts"), csp);
        assertFalse(csp.contains("allow-same-origin"), "never same-origin with the dashboard");
        assertTrue(r.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(404, get("/site/missing", null).statusCode());
        assertEquals(405, post("/site/", null, "text/plain", "x").statusCode());
    }

    @Test
    void foreignHostHeadersAreRefused() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            OutputStream out = s.getOutputStream();
            out.write(("GET /_nr/login HTTP/1.1\r\nHost: evil.example:" + server.port() + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            String status = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
            assertEquals("HTTP/1.1 421", status, "DNS rebinding: an unknown Host never reaches the app");
        }
    }

    @Test
    void traversalAndEncodedSlashesAreRefused() throws Exception {
        String cookie = login();
        assertEquals(400, get("/apps/t/../../etc/passwd", cookie).statusCode());
        assertEquals(400, get("/apps/%2e%2e/x", cookie).statusCode());
        assertEquals(400, get("/apps/t/a%2fb", cookie).statusCode());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String cookie = login();
        assertEquals(200, get("/apps/t", cookie).statusCode());
        assertEquals(303, post("/_nr/logout", cookie, "application/x-www-form-urlencoded", "", "Origin", base()).statusCode());
        assertEquals(303, get("/apps/t", cookie).statusCode(), "back to the login page");
    }

    @Test
    void staticAssetsAreHashedAndCacheable() throws Exception {
        String cookie = login();
        String body = get("/apps/t", cookie).body();
        String css = find(body, "href=\"(/_nr/kit-[0-9a-f]{16}\\.css)\"");
        String js = find(body, "src=\"(/_nr/runtime-[0-9a-f]{16}\\.js)\"");
        HttpResponse<String> c = get(css, null);
        assertEquals(200, c.statusCode());
        assertTrue(c.headers().firstValue("Cache-Control").orElseThrow().contains("immutable"));
        assertTrue(c.body().contains("--nr-accent"));
        assertTrue(get(js, null).body().contains("NR-UI/1"));
    }

    @Test
    void defaultSettingsAreValid() {
        WebServer.Config c = WebServer.fromSettings((k, d) -> k.equals("noray.web.hosts") ? "noray.lan, Example.ORG" : d);
        assertEquals("127.0.0.1", c.bind());
        assertTrue(c.hosts().containsAll(Set.of("127.0.0.1", "localhost", "[::1]", "noray.lan", "example.org")), c.hosts().toString());
        assertTrue(WebServer.fromSettings((k, d) -> k.equals("noray.web.bind") ? "::1" : d).hosts().contains("[::1]"));
    }

    @Test
    void redirectTargetsAndDownloadNamesAreSanitised() {
        assertEquals("/apps", invokeSafeNext("//evil.example"));
        assertEquals("/apps", invokeSafeNext("https://evil.example"));
        assertEquals("/apps", invokeSafeNext("/\\evil"));
        assertEquals("/apps/t", invokeSafeNext("/apps/t"));
    }

    private static String invokeSafeNext(String s) {
        try {
            var m = WebServer.class.getDeclaredMethod("safeNext", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, s);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private String event(String body, String regexToken, String type) {
        return "{\"v\":1,\"page\":\"" + find(body, "data-nr-page=\"([^\"]+)\"") + "\",\"token\":\""
                + find(body, regexToken) + "\",\"type\":\"" + type + "\",\"value\":\"\"}";
    }

    @Test
    void viewerTokensCanBrowseButTheirActionsAreRefused() throws Exception {
        String viewer = login(tokens.issue("ro", AccessTokens.Role.VIEWER));
        String page = get("/apps/t", viewer).body();
        String csrf = find(page, "data-nr-csrf=\"([^\"]+)\"");
        HttpResponse<String> r = post("/_nr/event", viewer, "application/json", event(page, "data-nr-on=\"click:([^\"]+)\"", "click"),
                "X-NR-CSRF", csrf, "Sec-Fetch-Site", "same-origin");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("Read-only access"), r.body());
        assertFalse(r.body().contains("count 1"), "the counter did not move");
    }

    @Test
    void uploadsArriveAsFilesAndOversizedOnesAreRefused() throws Exception {
        String cookie = login();
        String page = get("/apps/t/upload", cookie).body();
        String csrf = find(page, "data-nr-csrf=\"([^\"]+)\"");
        String action = find(page, "enctype=\"multipart/form-data\" action=\"([^\"]+)\"").replace("&amp;", "&");
        String boundary = "nrtestboundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"C:\\\\Users\\\\x\\\\a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nhello\r\n--" + boundary + "--\r\n";
        HttpResponse<String> r = post(action, cookie, "multipart/form-data; boundary=" + boundary, body,
                "X-NR-CSRF", csrf, "Sec-Fetch-Site", "same-origin");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(1, uploaded.size());
        assertEquals("a.txt", uploaded.getFirst().filename(), "only the last path segment is kept");
        assertEquals("hello", new String(uploaded.getFirst().data(), StandardCharsets.UTF_8));
        String big = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"b\"\r\n\r\n" + "x".repeat(5000)
                + "\r\n--" + boundary + "--\r\n";
        assertEquals(413, post(action, cookie, "multipart/form-data; boundary=" + boundary, big, "X-NR-CSRF", csrf,
                "Sec-Fetch-Site", "same-origin").statusCode());
        assertEquals(403, post(action, cookie, "multipart/form-data; boundary=" + boundary, body, "X-NR-CSRF", "wrong",
                "Sec-Fetch-Site", "same-origin").statusCode());
    }

    @Test
    void publicSitesCanHaveTheirOwnListener() throws Exception {
        Mounts mounts = new Mounts();
        mounts.mountSite("/", (path, req) -> Optional.of(new Site.FileContent("index.html", "own origin".getBytes())));
        // a site port of 0 means "share the dashboard's listener", so pick a real free port for the second listener
        int free;
        try (ServerSocket ss = new ServerSocket(0)) { free = ss.getLocalPort(); }
        WebServer.Config cfg = new WebServer.Config("127.0.0.1", 0, null, new char[0], false, Set.of("127.0.0.1"),
                Duration.ofMinutes(5), Duration.ofHours(1), "Noray", 4096, "127.0.0.1", free);
        try (WebServer two = new WebServer(cfg, mounts, tokens, Theme.noir())) {
            HttpResponse<String> site = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + two.sitePort() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("own origin", site.body());
            assertFalse(site.headers().firstValue("Content-Security-Policy").orElseThrow().contains("sandbox"), "its own origin");
            HttpResponse<String> onMain = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + two.port() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(303, onMain.statusCode(), "the dashboard listener no longer serves the site");
            HttpResponse<String> noDashboard = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + two.sitePort() + "/_nr/login")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertFalse(noDashboard.body().contains("Access token"), "the site listener never serves the login page");
        }
    }
}
