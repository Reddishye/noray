package com.redactado.noray.web.server;

import com.redactado.noray.web.Site;
import com.redactado.noray.web.html.Node;
import com.redactado.noray.web.kit.AppShell;
import com.redactado.noray.web.ui.Blob;
import com.redactado.noray.web.ui.Download;
import com.redactado.noray.web.ui.FormData;
import com.redactado.noray.web.ui.Json;
import com.redactado.noray.web.ui.MediaTypes;
import com.redactado.noray.web.ui.Page;
import com.redactado.noray.web.ui.PageView;
import com.redactado.noray.web.ui.RequestContext;
import com.redactado.noray.web.ui.Router;
import com.redactado.noray.web.ui.Theme;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static com.redactado.noray.web.html.Html.*;

/**
 * The HTTP side of the web UI (see docs/WEB_UI_DESIGN.md §5). JDK {@code HttpServer} on virtual threads, one handler.
 * Exposure rules: loopback unless configured; non-loopback needs TLS unless {@code insecure}; requests whose
 * {@code Host} is not in the allow-list are refused (DNS rebinding); every non-GET must be same-origin; events also
 * need the session's CSRF token.
 */
public final class WebServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("noray.web");
    private static final int MAX_BODY = 1024 * 1024;
    private static final Duration BLOB_TTL = Duration.ofMinutes(10);
    private static final String COOKIE = "nr_session";

    /** Settings; see {@link #fromSettings}. */
    /**
     * @param uploadMaxBytes largest file-upload request accepted
     * @param sitePort       0 = public sites share the dashboard's listener (sandboxed into an opaque origin); otherwise
     *                       they are served only on this port, which is a different origin
     */
    public record Config(String bind, int port, Path keystore, char[] keystorePassword, boolean insecure, Set<String> hosts,
                         Duration sessionIdle, Duration sessionLifetime, String brand, long uploadMaxBytes, String siteBind,
                         int sitePort) {

        /** Defaults for the optional fields: 64 MiB uploads, sites on the main listener. */
        public Config(String bind, int port, Path keystore, char[] keystorePassword, boolean insecure, Set<String> hosts,
                      Duration sessionIdle, Duration sessionLifetime, String brand) {
            this(bind, port, keystore, keystorePassword, insecure, hosts, sessionIdle, sessionLifetime, brand,
                    64L * 1024 * 1024, bind, 0);
        }
    }

    /** A single SSE patch larger than this is not pushed (the page reloads the region on its next action). */
    private static final int MAX_PUSHED_PATCH = 4 * 1024 * 1024;

    private final Config config;
    private final Mounts mounts;
    private final AccessTokens tokens;
    private final Sessions sessions;
    private final LoginThrottle throttle = new LoginThrottle();
    private final Theme theme;
    private final HttpServer server;
    private final HttpServer siteServer;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore concurrency = new Semaphore(512);
    private final boolean tls;
    private final byte[] runtimeJs;
    private final byte[] kitCss;
    private final String runtimePath;
    private final String kitPath;
    private volatile boolean closed;

    public WebServer(Config config, Mounts mounts, AccessTokens tokens, Theme theme) throws IOException {
        this.config = config;
        this.mounts = mounts;
        this.tokens = tokens;
        this.theme = theme;
        this.sessions = new Sessions(config.sessionIdle(), config.sessionLifetime());
        InetAddress addr = InetAddress.getByName(config.bind());
        this.tls = config.keystore() != null;
        if (!addr.isLoopbackAddress() && !tls && !config.insecure()) {
            throw new IllegalArgumentException("refusing to serve the web UI on " + config.bind()
                    + " without TLS (set noray.web.tls.keystore, or noray.web.insecure=true to accept the risk)");
        }
        if (!addr.isLoopbackAddress() && !tls) {
            LOG.log(System.Logger.Level.WARNING, "web UI on a non-loopback address WITHOUT TLS: tokens and sessions travel in clear");
        }
        this.runtimeJs = resource("runtime.js");
        this.kitCss = (theme.css() + new String(resource("kit.css"), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        this.runtimePath = "/_nr/runtime-" + shortHash(runtimeJs) + ".js";
        this.kitPath = "/_nr/kit-" + shortHash(kitCss) + ".css";
        InetSocketAddress socket = new InetSocketAddress(addr, config.port());
        if (tls) {
            HttpsServer s = HttpsServer.create(socket, 64);
            s.setHttpsConfigurator(new HttpsConfigurator(sslContext(config)));
            server = s;
        } else {
            server = HttpServer.create(socket, 64);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        if (config.sitePort() > 0) {
            InetSocketAddress siteSocket = new InetSocketAddress(InetAddress.getByName(config.siteBind()), config.sitePort());
            if (tls) {
                HttpsServer s = HttpsServer.create(siteSocket, 64);
                s.setHttpsConfigurator(new HttpsConfigurator(sslContext(config)));
                siteServer = s;
            } else {
                siteServer = HttpServer.create(siteSocket, 64);
            }
            siteServer.createContext("/", this::handleSite);
            siteServer.setExecutor(executor);
        } else {
            siteServer = null;
        }
        server.start();
        if (siteServer != null) siteServer.start();
        LOG.log(System.Logger.Level.INFO, "web UI on " + (tls ? "https" : "http") + "://" + config.bind() + ":" + port()
                + (siteServer == null ? "" : ", public sites on port " + sitePort()));
    }

    /** Reads {@code noray.web.*} settings. */
    public static Config fromSettings(BiFunction<String, String, String> get) {
        String bind = get.apply("noray.web.bind", "127.0.0.1");
        int port = Integer.parseInt(get.apply("noray.web.port", "0"));
        String ks = get.apply("noray.web.tls.keystore", "");
        String ksPass = get.apply("noray.web.tls.password", "");
        String passFile = get.apply("noray.web.tls.password.file", "");
        if (!passFile.isEmpty()) {
            try {
                ksPass = Files.readString(Path.of(passFile)).strip();
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read " + passFile, e);
            }
        }
        Set<String> hosts = new HashSet<>(List.of("127.0.0.1", "localhost", "[::1]"));
        hosts.add(bind.contains(":") && !bind.startsWith("[") ? "[" + bind + "]" : bind.toLowerCase(Locale.ROOT));
        for (String h : get.apply("noray.web.hosts", "").split(",")) if (!h.isBlank()) hosts.add(h.strip().toLowerCase(Locale.ROOT));
        return new Config(bind, port, ks.isEmpty() ? null : Path.of(ks), ksPass.toCharArray(),
                Boolean.parseBoolean(get.apply("noray.web.insecure", "false")), Set.copyOf(hosts),
                Duration.ofMinutes(Long.parseLong(get.apply("noray.web.session.idle.min", "30"))),
                Duration.ofHours(12), get.apply("noray.web.brand", "Noray"),
                Long.parseLong(get.apply("noray.web.upload.max.mib", "64")) * 1024 * 1024,
                get.apply("noray.web.site.bind", bind), Integer.parseInt(get.apply("noray.web.site.port", "0")));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** The public-site listener's port, or 0 when sites share the main listener. */
    public int sitePort() {
        return siteServer == null ? 0 : siteServer.getAddress().getPort();
    }

    public int sessionCount() {
        return sessions.size();
    }

    @Override
    public void close() {
        closed = true;
        server.stop(0);
        if (siteServer != null) siteServer.stop(0);
        executor.shutdownNow();
    }

    // ── dispatch ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private void handle(HttpExchange ex) {
        boolean stream = ex.getRequestURI().getRawPath().equals("/_nr/stream");
        if (!stream && !concurrency.tryAcquire()) {
            reply(ex, 503, "text/plain; charset=utf-8", "busy".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            route(ex);
        } catch (HttpError e) {
            reply(ex, e.status, "text/plain; charset=utf-8", e.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "web request failed: " + ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath(), t);
            reply(ex, 500, "text/plain; charset=utf-8", "internal error".getBytes(StandardCharsets.UTF_8));
        } finally {
            if (!stream) concurrency.release();
            ex.close();
        }
    }

    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private void route(HttpExchange ex) throws IOException {
        checkHost(ex);
        String method = ex.getRequestMethod();
        String raw = ex.getRequestURI().getRawPath();
        String path = Router.normalise(raw).orElseThrow(() -> new HttpError(400, "bad path"));
        Map<String, String> query = query(ex.getRequestURI().getRawQuery());

        if (path.equals(runtimePath)) { asset(ex, "text/javascript; charset=utf-8", runtimeJs); return; }
        if (path.equals(kitPath)) { asset(ex, "text/css; charset=utf-8", kitCss); return; }
        if (path.equals("/_nr/login")) { login(ex, method, query); return; }
        if (path.equals("/favicon.ico")) { reply(ex, 404, "text/plain", new byte[0]); return; }

        Optional<Map.Entry<Mounts.MountedSite, String>> site = siteServer != null || path.startsWith("/_nr/") || path.startsWith("/apps")
                ? Optional.empty() : mounts.site(path);
        if (site.isPresent()) { site(ex, method, site.get().getKey(), site.get().getValue(), path, query, true); return; }

        Optional<Sessions.Session> session = sessions.get(cookie(ex, COOKIE));
        if (session.isEmpty()) {
            if (method.equals("GET") && !path.startsWith("/_nr/")) {
                redirect(ex, "/_nr/login?next=" + URLEncoder.encode(path, StandardCharsets.UTF_8));
            } else {
                throw new HttpError(401, "sign in first");
            }
            return;
        }
        Sessions.Session s = session.get();
        if (!method.equals("GET") && !method.equals("HEAD")) checkSameOrigin(ex);

        switch (path) {
            case "/_nr/event" -> event(ex, s);
            case "/_nr/form" -> formPost(ex, s, query);
            case "/_nr/upload" -> upload(ex, s, query);
            case "/_nr/stream" -> stream(ex, s, query);
            case "/_nr/logout" -> logout(ex, s);
            case "/", "/apps" -> home(ex, s, path);
            default -> {
                if (path.startsWith("/_nr/blob/")) blob(ex, s, path.substring("/_nr/blob/".length()));
                else if (path.startsWith("/apps/")) app(ex, s, path, query);
                else throw new HttpError(404, "not found");
            }
        }
    }

    // ── apps and pages ────────────────────────────────────────────────────────────────────────────────────────────────

    private void home(HttpExchange ex, Sessions.Session s, String path) throws IOException {
        List<Node> cards = new ArrayList<>();
        for (Mounts.MountedApp m : mounts.mountedApps()) {
            List<Node> links = new ArrayList<>();
            for (Router.NavEntry e : m.router().navEntries()) links.add(li(a(href(appUrl(m.slug(), e.appPath())), e.label())));
            cards.add(section(cls("nr-card"), div(cls("nr-card-h"), h2(a(href(appUrl(m.slug(), "/")), m.app().title())),
                            m.owner().equals("core") ? null : span(cls("nr-badge", "nr-badge-plain"), m.owner())),
                    div(cls("nr-card-b"), links.isEmpty() ? p(cls("nr-muted"), "No pages") : ul(links))));
        }
        Node body = div(cls("nr-stack"), div(cls("nr-page-h"), div(h1("Apps"), p(cls("nr-muted"), "Everything mounted on this node."))),
                cards.isEmpty() ? p("No apps are mounted.") : div(cls("nr-grid"), cards));
        sendPage(ex, s, "Apps", body, null, false, path);
    }

    private void app(HttpExchange ex, Sessions.Session s, String path, Map<String, String> query) throws IOException {
        String rest = path.substring("/apps/".length());
        int slash = rest.indexOf('/');
        String slug = slash < 0 ? rest : rest.substring(0, slash);
        String appPath = slash < 0 ? "/" : rest.substring(slash);
        Mounts.MountedApp m = mounts.app(slug).orElseThrow(() -> new HttpError(404, "no app at /apps/" + slug));
        Router.Match match = m.router().match(appPath).orElseThrow(() -> new HttpError(404, "not found"));
        Ctx ctx = new Ctx(path, match.params(), query, Optional.of(s.user), slug);
        switch (match.target()) {
            case Router.PageTarget pt -> {
                Page page = pt.handler().apply(ctx);
                if (page == null) throw new HttpError(404, "not found");
                PageView view = new PageView(page, environment(s, ctx, true));
                Node body = view.renderBody();
                s.addPage(view);
                sendPage(ex, s, page.title(), body, view, view.hasTimedRegions(), path);
            }
            case Router.DownloadTarget dt -> {
                Download d = dt.handler().apply(ctx);
                if (d == null) throw new HttpError(404, "not found");
                attachment(ex, d.filename(), d.data());
            }
        }
    }

    private PageView.Environment environment(Sessions.Session s, Ctx ctx, boolean interactive) {
        return new PageView.Environment() {
            @Override public String link(String appPath) { return ctx.link(appPath); }
            @Override public String blob(Blob blob) {
                if (s == null) throw new IllegalStateException("public pages cannot serve blobs");
                return "/_nr/blob/" + s.addBlob(blob);
            }
            @Override public RequestContext request() { return ctx; }
            @Override public String formEndpoint() {
                return "/_nr/form?back=" + URLEncoder.encode(ctx.path(), StandardCharsets.UTF_8) + "&t=";
            }
            @Override public boolean interactive() { return interactive; }
        };
    }

    private void sendPage(HttpExchange ex, Sessions.Session s, String title, Node content, PageView view, boolean stream,
                          String path) throws IOException {
        List<AppShell.NavGroup> groups = new ArrayList<>();
        for (Mounts.MountedApp m : mounts.mountedApps()) {
            List<AppShell.NavLink> links = new ArrayList<>();
            for (Router.NavEntry e : m.router().navEntries()) links.add(new AppShell.NavLink(e.label(), appUrl(m.slug(), e.appPath())));
            if (links.isEmpty()) links.add(new AppShell.NavLink(m.app().title(), appUrl(m.slug(), "/")));
            groups.add(new AppShell.NavGroup(m.app().title(), links));
        }
        Node shell = AppShell.render(config.brand(), groups, path, title, "/_nr/logout", content);
        String nonce = Sessions.random(18);
        String html = document(title, shell, nonce, view == null ? "none" : view.id(), s.csrf, stream, themeOf(ex), true);
        securityHeaders(ex, nonce, true);
        reply(ex, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private String document(String title, Node body, String nonce, String pageId, String csrf, boolean stream, String themeAttr,
                            boolean withRuntime) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!doctype html><html lang=\"en\"");
        if (themeAttr != null) sb.append(" data-theme=\"").append(themeAttr).append('"');
        sb.append("><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta name=\"referrer\" content=\"no-referrer\"><title>");
        new Node.Text(title + " · " + config.brand()).render(sb);
        sb.append("</title><link rel=\"stylesheet\" href=\"").append(kitPath).append("\">");
        if (withRuntime) sb.append("<script nonce=\"").append(nonce).append("\" src=\"").append(runtimePath).append("\" defer></script>");
        sb.append("</head><body");
        if (withRuntime) {
            sb.append(" data-nr-page=\"").append(pageId).append("\" data-nr-csrf=\"").append(csrf).append('"');
            if (stream) sb.append(" data-nr-stream=\"1\"");
        }
        sb.append('>');
        body.render(sb);
        sb.append("</body></html>");
        return sb.toString();
    }

    // ── events, forms, streams ────────────────────────────────────────────────────────────────────────────────────────

    private void event(HttpExchange ex, Sessions.Session s) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) throw new HttpError(405, "POST only");
        String header = ex.getRequestHeaders().getFirst("X-NR-CSRF");
        if (header == null || !MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), s.csrf.getBytes(StandardCharsets.UTF_8))) {
            throw new HttpError(403, "bad CSRF token");
        }
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(new String(body(ex), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "bad event: " + e.getMessage());
        }
        if (!Double.valueOf(1).equals(msg.get("v"))) throw new HttpError(400, "unsupported protocol version");
        PageView view = s.page(String.valueOf(msg.get("page"))).orElseThrow(() -> new HttpError(410, "page expired"));
        FormData form = msg.get("form") instanceof Map<?, ?> f ? formData(f) : FormData.EMPTY;
        PageView.Result r = view.dispatch(String.valueOf(msg.get("token")), msg.get("value") == null ? "" : String.valueOf(msg.get("value")),
                form, s.operator());
        sendResult(ex, r);
    }

    private void sendResult(HttpExchange ex, PageView.Result r) {
        StringBuilder sb = new StringBuilder("{\"v\":1,\"patches\":[");
        for (int i = 0; i < r.patches().size(); i++) {
            if (i > 0) sb.append(',');
            patchJson(r.patches().get(i), sb);
        }
        sb.append("],\"navigate\":").append(Json.quote(r.navigate())).append(",\"toasts\":[");
        for (int i = 0; i < r.toasts().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"level\":").append(Json.quote(r.toasts().get(i).level().name())).append(",\"message\":")
                    .append(Json.quote(r.toasts().get(i).message())).append('}');
        }
        sb.append("]}");
        securityHeaders(ex, null, true);
        reply(ex, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** No-JavaScript form posts: same-origin was checked; run the action and go back to the page (POST-redirect-GET). */
    private void formPost(HttpExchange ex, Sessions.Session s, Map<String, String> query) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) throw new HttpError(405, "POST only");
        String token = query.getOrDefault("t", "");
        Map<String, List<String>> fields = new LinkedHashMap<>();
        String bodyText = new String(body(ex), StandardCharsets.UTF_8);
        for (String pair : bodyText.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            fields.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        for (PageView view : s.pageList()) {
            PageView.Result r = view.dispatch(token, "", new FormData(fields), s.operator());
            if (r.navigate() != null) { redirect(ex, safeNext(r.navigate())); return; }
            if (!r.patches().isEmpty() || !r.toasts().isEmpty()) break;
        }
        redirect(ex, safeNext(query.getOrDefault("back", "/apps")));
    }

    /**
     * File uploads ({@code multipart/form-data}). With the runtime's CSRF header the answer is the usual JSON result;
     * a plain form post (no JavaScript) is redirected back to its page. Same-origin was checked for every non-GET.
     */
    private void upload(HttpExchange ex, Sessions.Session s, Map<String, String> query) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) throw new HttpError(405, "POST only");
        String header = ex.getRequestHeaders().getFirst("X-NR-CSRF");
        boolean viaRuntime = header != null;
        if (viaRuntime && !MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), s.csrf.getBytes(StandardCharsets.UTF_8))) {
            throw new HttpError(403, "bad CSRF token");
        }
        byte[] raw = body(ex, config.uploadMaxBytes());
        FormData form;
        try {
            form = Multipart.parse(raw, ex.getRequestHeaders().getFirst("Content-Type"));
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "bad upload: " + e.getMessage());
        }
        String token = query.getOrDefault("t", "");
        PageView.Result result = new PageView.Result(List.of(), null, List.of());
        for (PageView view : s.pageList()) {
            PageView.Result r = view.dispatch(token, "", form, s.operator());
            if (r.navigate() != null || !r.patches().isEmpty() || !r.toasts().isEmpty()) {
                result = r;
                break;
            }
        }
        if (viaRuntime) {
            sendResult(ex, result);
        } else {
            redirect(ex, safeNext(result.navigate() != null ? result.navigate() : query.getOrDefault("back", "/apps")));
        }
    }

    private void stream(HttpExchange ex, Sessions.Session s, Map<String, String> query) throws IOException {
        PageView view = s.page(query.getOrDefault("page", "")).orElse(null);
        if (view == null) {
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", "text/event-stream");
            h.set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("event: gone\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (s.streams.incrementAndGet() > Sessions.MAX_STREAMS) {
            s.streams.decrementAndGet();
            throw new HttpError(429, "too many streams");
        }
        try {
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", "text/event-stream");
            h.set("Cache-Control", "no-store");
            h.set("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            long lastBeat = System.nanoTime();
            while (!closed && sessions.get(s.id).isPresent() && s.page(view.id()).isPresent()) {
                view.touch();
                for (PageView.Patch p : view.due(Instant.now())) {
                    if (p.html().length() > MAX_PUSHED_PATCH) {
                        LOG.log(System.Logger.Level.WARNING, "not pushing region " + p.region() + " of '" + view.title()
                                + "': " + p.html().length() + " characters");
                        continue;
                    }
                    StringBuilder sb = new StringBuilder("event: patch\ndata: ");
                    patchJson(p, sb);
                    out.write(sb.append("\n\n").toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                if (System.nanoTime() - lastBeat > Duration.ofSeconds(15).toNanos()) {
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    lastBeat = System.nanoTime();
                }
                Thread.sleep(250);
            }
            out.write("event: gone\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException closedByClient) {
            // the browser went away
        } finally {
            s.streams.decrementAndGet();
        }
    }

    private static void patchJson(PageView.Patch p, StringBuilder sb) {
        sb.append("{\"region\":").append(Json.quote(p.region())).append(",\"html\":").append(Json.quote(p.html())).append('}');
    }

    private static FormData formData(Map<?, ?> raw) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            List<String> values = new ArrayList<>();
            if (v instanceof List<?> l) for (Object o : l) values.add(String.valueOf(o));
            else values.add(String.valueOf(v));
            fields.put(String.valueOf(k), values);
        });
        return new FormData(fields);
    }

    // ── blobs ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    private void blob(HttpExchange ex, Sessions.Session s, String token) throws IOException {
        Blob b = s.blob(token, BLOB_TTL).orElseThrow(() -> new HttpError(404, "expired"));
        byte[] data = b.bytes().get();
        MediaTypes.Type t = MediaTypes.detect(b.filename(), Arrays.copyOf(data, Math.min(data.length, 4096)));
        boolean inline = t.kind() == MediaTypes.Kind.IMAGE || t.kind() == MediaTypes.Kind.AUDIO || t.kind() == MediaTypes.Kind.VIDEO;
        boolean wantsDownload = "application/octet-stream".equals(b.mediaType());
        Headers h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", "default-src 'none'; sandbox");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Cache-Control", "private, no-store");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        h.set("Accept-Ranges", "bytes");
        if (inline && !wantsDownload) {
            h.set("Content-Disposition", "inline; " + dispositionName(b.filename()));
            range(ex, t.mediaType(), data);
        } else {
            h.set("Content-Disposition", "attachment; " + dispositionName(b.filename()));
            reply(ex, 200, "application/octet-stream", data);
        }
    }

    /** One {@code Range: bytes=a-b} (media seeking); anything else gets the whole body. */
    private static void range(HttpExchange ex, String type, byte[] data) throws IOException {
        String r = ex.getRequestHeaders().getFirst("Range");
        if (r != null && r.matches("bytes=\\d*-\\d*") && !r.equals("bytes=-")) {
            String[] p = r.substring(6).split("-", -1);
            long start, end;
            if (p[0].isEmpty()) {
                long n = Long.parseLong(p[1]);
                start = Math.max(0, data.length - n);
                end = data.length - 1;
            } else {
                start = Long.parseLong(p[0]);
                end = p[1].isEmpty() ? data.length - 1 : Math.min(Long.parseLong(p[1]), data.length - 1);
            }
            if (start > end || start >= data.length) {
                ex.getResponseHeaders().set("Content-Range", "bytes */" + data.length);
                reply(ex, 416, type, new byte[0]);
                return;
            }
            ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            reply(ex, 206, type, Arrays.copyOfRange(data, (int) start, (int) end + 1));
            return;
        }
        reply(ex, 200, type, data);
    }

    private void attachment(HttpExchange ex, String filename, byte[] data) throws IOException {
        Headers h = ex.getResponseHeaders();
        securityHeaders(ex, null, true);
        h.set("Content-Disposition", "attachment; " + dispositionName(filename));
        h.set("Content-Security-Policy", "default-src 'none'; sandbox");
        reply(ex, 200, "application/octet-stream", data);
    }

    /** {@code filename="ascii"; filename*=UTF-8''percent-encoded}: no quotes, slashes or controls survive. */
    static String dispositionName(String name) {
        String n = name == null || name.isBlank() ? "download" : name;
        n = n.replaceAll("[\\p{Cntrl}\"\\\\/;]", "_");
        String ascii = n.replaceAll("[^\\x20-\\x7e]", "_");
        String encoded = URLEncoder.encode(n, StandardCharsets.UTF_8).replace("+", "%20");
        return "filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    // ── public sites ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** The public-site listener: sites only (plus the stylesheet their pages use); no sessions, no dashboard. */
    private void handleSite(HttpExchange ex) {
        if (!concurrency.tryAcquire()) {
            reply(ex, 503, "text/plain; charset=utf-8", "busy".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            String path = Router.normalise(ex.getRequestURI().getRawPath()).orElseThrow(() -> new HttpError(400, "bad path"));
            if (path.equals(kitPath)) {
                asset(ex, "text/css; charset=utf-8", kitCss);
                return;
            }
            Map.Entry<Mounts.MountedSite, String> site = mounts.site(path).orElseThrow(() -> new HttpError(404, "not found"));
            site(ex, ex.getRequestMethod(), site.getKey(), site.getValue(), path, query(ex.getRequestURI().getRawQuery()), false);
        } catch (HttpError e) {
            reply(ex, e.status, "text/plain; charset=utf-8", e.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "site request failed: " + ex.getRequestURI().getRawPath(), t);
            reply(ex, 500, "text/plain; charset=utf-8", "internal error".getBytes(StandardCharsets.UTF_8));
        } finally {
            concurrency.release();
            ex.close();
        }
    }

    /** @param sandboxed true on the dashboard's listener: the site must not share the dashboard's origin */
    private void site(HttpExchange ex, String method, Mounts.MountedSite m, String rel, String path, Map<String, String> query,
                      boolean sandboxed) throws IOException {
        if (!method.equals("GET") && !method.equals("HEAD")) throw new HttpError(405, "read-only site");
        Ctx ctx = new Ctx(path, Map.of(), query, Optional.empty(), null);
        Optional<Site.Content> content = m.site().get(rel, ctx);
        Headers h = ex.getResponseHeaders();
        // on the dashboard's listener, an opaque origin: the site's scripts can never read the dashboard with the operator's
        // cookies. On its own listener it is already a different origin (and every dashboard non-GET demands same-origin).
        h.set("Content-Security-Policy", sandboxed ? "sandbox allow-scripts allow-forms allow-popups allow-downloads; frame-ancestors 'none'"
                : "frame-ancestors 'none'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        if (content.isEmpty()) {
            reply(ex, 404, "text/html; charset=utf-8", document("Not found", div(cls("nr-content"), h1("Not found"),
                    p("Nothing here.")), "", "", "", false, null, false).getBytes(StandardCharsets.UTF_8));
            return;
        }
        switch (content.get()) {
            case Site.PageContent pc -> {
                PageView view = new PageView(pc.page(), environment(null, ctx, false));
                Node body = view.renderBody();
                h.set("Cache-Control", "no-cache");
                reply(ex, 200, "text/html; charset=utf-8", document(pc.page().title(), main(cls("nr-content"), body), "", "", "",
                        false, null, false).getBytes(StandardCharsets.UTF_8));
            }
            case Site.FileContent fc -> {
                String etag = "\"" + shortHash(fc.data()) + "\"";
                h.set("ETag", etag);
                h.set("Cache-Control", "public, max-age=60");
                if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
                    ex.sendResponseHeaders(304, -1);
                    return;
                }
                range(ex, SiteTypes.of(fc.name()), fc.data());
            }
            case Site.Redirect r -> {
                href(r.location()); // same validation as a link
                redirect(ex, r.location());
            }
        }
    }

    // ── login ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    private void login(HttpExchange ex, String method, Map<String, String> query) throws IOException {
        String next = safeNext(query.getOrDefault("next", "/apps"));
        if (method.equals("POST")) {
            checkSameOrigin(ex);
            String client = ex.getRemoteAddress().getAddress().getHostAddress();
            long wait = throttle.waitMillis(client, System.currentTimeMillis());
            if (wait > 0) {
                loginPage(ex, next, "Too many attempts. Try again in " + Math.max(1, wait / 1000) + " s.", 429);
                return;
            }
            Map<String, String> f = query(new String(body(ex), StandardCharsets.UTF_8));
            Optional<AccessTokens.Grant> who = tokens.authenticate(f.getOrDefault("token", ""));
            if (who.isEmpty()) {
                throttle.failed(client, System.currentTimeMillis());
                LOG.log(System.Logger.Level.WARNING, "web login failed from " + client);
                loginPage(ex, next, "That token is not valid.", 403);
                return;
            }
            throttle.succeeded(client);
            sessions.remove(cookie(ex, COOKIE)); // never reuse a session id from before login
            Sessions.Session s = sessions.create(who.get().role().name().toLowerCase(Locale.ROOT) + ":" + who.get().label());
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + s.id + "; Path=/; HttpOnly; SameSite=Strict" + (tls ? "; Secure" : ""));
            LOG.log(System.Logger.Level.INFO, "web login by token '" + who.get().label() + "' (" + who.get().role() + ") from " + client);
            redirect(ex, next);
            return;
        }
        loginPage(ex, next, tokens.isEmpty() ? "No access token exists yet: run `web token new` in the node's shell." : null, 200);
    }

    private void loginPage(HttpExchange ex, String next, String error, int status) throws IOException {
        Node body = div(cls("nr-login"), section(cls("nr-card"), aria("label", "Sign in"),
                div(cls("nr-card-h"), h2(span(cls("nr-brand-mark"), aria("hidden", "true"), "⚓"), " ", config.brand())),
                div(cls("nr-card-b"),
                        error == null ? null : div(cls("nr-alert", "nr-alert-error"), role("alert"), error),
                        form(method("post"), action("/_nr/login?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8)),
                                label(cls("nr-field"), forId("token"), span("Access token"),
                                        input(id("token"), name("token"), type("password"), attr("autocomplete", "current-password"),
                                                required(), attr("autofocus", null))),
                                button(type("submit"), cls("nr-btn", "nr-btn-primary"), "Sign in")),
                        p(cls("nr-hint"), "Create a token on the node with ", code("web token new"), "."))));
        securityHeaders(ex, null, true);
        reply(ex, status, "text/html; charset=utf-8",
                document("Sign in", body, "", "", "", false, themeOf(ex), false).getBytes(StandardCharsets.UTF_8));
    }

    private void logout(HttpExchange ex, Sessions.Session s) throws IOException {
        sessions.remove(s.id);
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + (tls ? "; Secure" : ""));
        redirect(ex, "/_nr/login");
    }

    /** Only same-site paths: no scheme, no protocol-relative {@code //host}, no backslashes. */
    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.contains("\\") || next.contains("\r")
                || next.contains("\n") || next.startsWith("/_nr/")) {
            return "/apps";
        }
        return Router.normalise(next).orElse("/apps");
    }

    // ── checks ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** DNS rebinding: a page on evil.example resolved to 127.0.0.1 sends {@code Host: evil.example}. */
    private void checkHost(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) throw new HttpError(400, "missing Host");
        String h = host.toLowerCase(Locale.ROOT);
        String name = h.startsWith("[") ? h.substring(0, h.indexOf(']') + 1) : (h.contains(":") ? h.substring(0, h.indexOf(':')) : h);
        if (!config.hosts().contains(name)) throw new HttpError(421, "unknown host; add it to noray.web.hosts");
    }

    private void checkSameOrigin(HttpExchange ex) {
        Headers h = ex.getRequestHeaders();
        String site = h.getFirst("Sec-Fetch-Site");
        if (site != null) {
            if (!site.equals("same-origin")) throw new HttpError(403, "cross-site request refused");
            return;
        }
        String origin = h.getFirst("Origin");
        if (origin == null) throw new HttpError(403, "missing Origin");
        String expected = (tls ? "https://" : "http://") + h.getFirst("Host");
        if (!origin.equalsIgnoreCase(expected)) throw new HttpError(403, "cross-origin request refused");
    }

    private void securityHeaders(HttpExchange ex, String nonce, boolean privateContent) {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", "default-src 'none'; script-src " + (nonce == null ? "'none'" : "'nonce-" + nonce + "'")
                + "; style-src 'self'; img-src 'self'; media-src 'self'; connect-src 'self'; form-action 'self'; "
                + "frame-ancestors 'none'; base-uri 'none'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), usb=(), payment=(), interest-cohort=()");
        h.set("X-Frame-Options", "DENY");
        if (privateContent) h.set("Cache-Control", "no-store");
        if (tls) h.set("Strict-Transport-Security", "max-age=31536000");
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private void asset(HttpExchange ex, String type, byte[] data) {
        Headers h = ex.getResponseHeaders();
        h.set("Cache-Control", "public, max-age=31536000, immutable");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        reply(ex, 200, type, data);
    }

    private static void redirect(HttpExchange ex, String location) {
        ex.getResponseHeaders().set("Location", location);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        reply(ex, 303, "text/plain", new byte[0]);
    }

    private static void reply(HttpExchange ex, int status, String type, byte[] data) {
        try {
            ex.getResponseHeaders().set("Content-Type", type);
            boolean head = ex.getRequestMethod().equals("HEAD");
            ex.sendResponseHeaders(status, head || data.length == 0 ? -1 : data.length);
            if (!head && data.length > 0) ex.getResponseBody().write(data);
        } catch (IOException ignored) {
            // client went away
        }
    }

    private static byte[] body(HttpExchange ex) throws IOException {
        return body(ex, MAX_BODY);
    }

    private static byte[] body(HttpExchange ex, long max) throws IOException {
        String declared = ex.getRequestHeaders().getFirst("Content-Length");
        if (declared != null && declared.matches("\\d{1,18}") && Long.parseLong(declared) > max) {
            throw new HttpError(413, "request body too large");
        }
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
            if (b.length > max) throw new HttpError(413, "request body too large");
            return b;
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty() || out.size() > 64) continue;
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.putIfAbsent(k, v);
            } catch (IllegalArgumentException ignored) {
                // malformed escape: skip
            }
        }
        return out;
    }

    private static String cookie(HttpExchange ex, String name) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String p = part.strip();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static String themeOf(HttpExchange ex) {
        String t = cookie(ex, "nr_theme");
        return "light".equals(t) || "dark".equals(t) ? t : null;
    }

    private static String appUrl(String slug, String appPath) {
        return "/apps/" + slug + (appPath.equals("/") ? "" : appPath.startsWith("/") ? appPath : "/" + appPath);
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream("/com/redactado/noray/web/" + name)) {
            if (in == null) throw new IOException("missing resource " + name);
            return in.readAllBytes();
        }
    }

    static String shortHash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SSLContext sslContext(Config c) throws IOException {
        try (InputStream in = Files.newInputStream(c.keystore())) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, c.keystorePassword());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, c.keystorePassword());
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot load TLS keystore " + c.keystore() + ": " + e.getMessage(), e);
        }
    }

    /** What a page's components see of the request. */
    private record Ctx(String path, Map<String, String> params, Map<String, String> queryParams, Optional<String> user,
                       String slug) implements RequestContext {
        @Override public String param(String name) { return params.getOrDefault(name, ""); }
        @Override public Optional<String> query(String name) { return Optional.ofNullable(queryParams.get(name)); }
        @Override public String link(String appPath) {
            if (slug == null) return appPath;
            return appUrl(slug, appPath);
        }
    }
}
