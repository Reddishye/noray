# Noray Web UI — design

A core module (`com.redactado.noray.web`) that lets any module build operator dashboards, public websites, file previewers
and interactive tools **from Java code only**: no HTML templates, no JavaScript, no frontend toolchain, no dependencies.
The node's own dashboard is built with it, which is the test that the API is good enough for others.

This document is the reference for the API's shape and the rules that keep it safe and maintainable for years. Everything
described here is implemented; §10 lists what is still open.

## 1. Goals, non-goals, constraints

**Goals**

1. A module writes `Node render(...)` methods and gets a working, accessible, themed page: tables, forms, charts, file
   previews, live-updating tiles, navigation.
2. Interactivity without client code: clicks, form submits and server pushes are handled in Java; the browser runs one
   small fixed runtime.
3. Safe by construction: there is no raw-HTML node at all, so module code cannot produce markup that was not built and
   escaped by the node tree. The Content Security Policy forbids every script but the runtime.
4. Capability-scoped like everything else in Noray: a sandboxed module can only mount UI under its own prefix and only sees
   data its grants allow.
5. Cheap to keep alive: one Java package tree, one ~10 KB hand-written script, one stylesheet generated from theme tokens.
   Upgrading the JDK is the only maintenance.

**Non-goals**

- A general web framework for the internet at large (SEO tooling, client-side routing, SPA state management).
- Running module JavaScript in the browser. Client behaviours are a closed, versioned set provided by the runtime (§5.4).
- Multi-tenant hosting of untrusted sites with their own server code; public sites are static files plus core-rendered pages.

**Constraints inherited from the core**

- Java only, no new dependencies: the HTTP layer is the JDK's `com.sun.net.httpserver` with virtual threads (already used for
  `/metrics`). It has no WebSocket, so server push is Server-Sent Events.
- Hostile infrastructure: bind to loopback by default, refuse non-loopback without TLS, no third-party requests from pages
  (no CDNs, fonts, analytics), no `Referer` leakage, nothing cached that is private.

## 2. Layers

```
 modules (node dashboard, file browser, static hosting, repo viewer, ...)
 ---------------------------------------------------------------------------
 web.kit      components: AppShell, Card, Stat, Table, Form, Tabs, Chart (SVG), Markdown, CodeView, FilePreview, Tree...
 web.ui       Page, Component, Live regions, actions, forms, server push, routing, theme
 web.server   HTTP, sessions, login, CSRF, security headers, SSE, blob serving, static sites, limits
 web.html     Node tree, escaping, attribute validation, rendering (pure, no I/O)
```

Each layer depends only on the ones below. `web.html` has no dependency on anything in Noray and is where every safety
property of the output is enforced, so it can be reviewed in isolation.

## 3. `web.html`: the node tree

```java
import static com.redactado.noray.web.html.Html.*;

Node card = div(cls("nr-card"),
        h2("Volumes"),
        p("Healthy: ", strong(String.valueOf(n))),
        a(href("/apps/fs/browse"), "Browse"));
```

- `Node` is a sealed interface: `Element`, `Text`, `Fragment`. Builders take parts: nodes, attributes, strings and numbers
  (always text nodes, always escaped), collections of parts, and `null` (skipped, handy for conditionals). Anything else throws.
- **Text is always escaped**; attribute values are always quoted and escaped.
- **Attribute names are validated** (`[a-zA-Z_:][-a-zA-Z0-9_:.]*`); `on*` handlers and `style` are refused (inline script and
  inline style are blocked by CSP anyway; refusing them early turns a silent breakage into an exception in tests).
- **URL attributes** (`href`, `src`, `action`, `formaction`, `poster`, `xlink:href`, `cite`) accept relative URLs and
  `http:`, `https:`, `mailto:` only; `javascript:`, `data:`, `vbscript:` and anything unparseable throw.
- **Element names are validated**; `script`, `style`, `iframe`, `object`, `embed`, `base`, `frame`, `link`, `meta` cannot be
  created through the DSL. The page shell (core) emits the one script and stylesheet with the request's CSP nonce.
- There is **no raw-HTML node**. The page shell, the only place that needs a `<script>` (with the request's CSP nonce), is
  written by the server, not by the tree. Markdown is not rendered to HTML text: it is parsed into nodes, so it inherits the
  guarantees above. SVG animation elements (`animate`, `set`, ...) and `foreignObject` are refused (known script vectors).
- Rendering is deterministic (attribute order preserved, no randomness), which makes snapshot tests stable.

## 4. `web.ui`: pages, components, interaction

### 4.1 Model

```java
public interface Component { Node render(Render r); }

routes.page("/", ctx -> Page.of("Overview", r -> div(
        r.live(new MetricsTiles(), Duration.ofSeconds(2)),     // re-rendered and pushed every 2 s
        r.live(new VolumesTable(volumes)))));                   // re-rendered when one of its actions runs
```

- A **page view** (`PageView`) is created per GET. It holds the actions and live regions registered while rendering.
- `r.live(component)` renders the component inside a region `<div data-nr-region="id">`. A region is the unit of update;
  `r.live(component, interval)` is also re-rendered and pushed on a timer.
- `r.onClick(Runnable)` / `r.on("change", Consumer<Event>)` return an attribute (`data-nr-on="click:<token>"`); the token is 128
  random bits, bound to the page view and the session. When the event arrives the callback runs and **the region it was
  created in is re-rendered** and sent back. Through `event.ctx()` a callback can also `refreshPage()`, `navigate(url)` or
  `toast(...)`. Re-rendering a region replaces its tokens, so a replayed token does nothing.
- `r.onSubmit(...)` does the same for a `<form>`: the runtime posts the fields as JSON; without JavaScript the form posts
  normally to the same token and the server answers with a redirect back. `r.onUpload(...)` is the multipart variant.
- `r.onView(...)` marks an action as view-only (sorting, filtering, paging, tabs): read-only viewers may run it. Every other
  action needs operator access.
- Component state is plain Java fields in the component object, held in the page view on the server. Nothing is serialized to
  the client, so there is nothing for a client to forge beyond "this button was clicked".

### 4.2 Wire protocol `NR-UI/1`

- `POST /_nr/event` with headers `X-NR-CSRF` and a JSON body `{"v":1,"page":"…","token":"…","value":"…","form":{…}}`.
- Response: `{"v":1,"patches":[{"region":"r3","html":"…"}],"navigate":null,"toasts":[…]}`.
- `GET /_nr/stream?page=…` (SSE): `event: patch` with the same patch object, for timed live regions.
- `POST /_nr/upload?t=…` (multipart) answers like `/_nr/event`.
- Unknown `v` → 400. Additions are new optional fields; a breaking change is `v:2` and the runtime is served at a versioned URL.

### 4.3 Client runtime (`/_nr/runtime-<hash>.js`, hand-written, no build step)

- Delegated event listener: one listener per event type on `document`, reads `data-nr-on`; `input` events are debounced.
- DOM **morphing** for patches (same tag + same `id`/`data-nr-key` keeps the element), so focus, caret and scroll survive a
  re-render; inputs being edited are not overwritten.
- An `EventSource` per page view that has timed regions (the browser reconnects it), closed on `pagehide`.
- `data-nr-confirm="question"` asks before an action is sent.
- A closed set of **behaviours** by name (`data-nr-b`): `copy` (clipboard), `dialog-open`, `dialog-close`, `autofocus`,
  `theme` (toggle). Adding one is a versioned runtime change, reviewed like a protocol change.

### 4.4 Routing

In `WebApp.routes(Routes r)`: `r.page(pattern, handler)`, `r.download(pattern, handler)` and `r.nav(label, path)`. Patterns:
literal segments, `{name}`, and a final `{*rest}`. Paths are decoded once and normalised; `..`, encoded slashes, backslashes
and NUL are refused before any handler sees them.

### 4.5 Theme

A `Theme` record of design tokens (palette, spacing scale, radii, font stacks, transition times) generates CSS custom
properties. `kit.css` only uses the variables; light/dark follow `prefers-color-scheme` and a toggle stored in a cookie. No
webfonts are downloaded: font stacks name locally installed fonts and fall back to the system's.

## 5. `web.server`

### 5.1 Exposure and authentication

- `noray.web.port` (0 = off), `noray.web.bind` (default `127.0.0.1`). A non-loopback bind **requires** TLS
  (`noray.web.tls.keystore`, PKCS#12) unless `noray.web.insecure=true` is set, which logs a warning at every start.
- Operator login with an access token: `web token new` prints a random token once and stores only its SHA-256
  (`<data>/web/tokens`). Comparison is constant-time; failed logins back off exponentially per client address.
- Sessions: 256-bit random id in an `HttpOnly; SameSite=Strict; Path=/` cookie (`Secure` with TLS), a new id after login (no
  fixation), idle timeout (`noray.web.session.idle.min`, 30), absolute lifetime (12 h), logout.
- CSRF: every non-GET must be same-origin (`Sec-Fetch-Site`, or `Origin` when that header is absent), and requests made by
  the runtime also carry the session's token in `X-NR-CSRF`. No-JS form posts rely on the same-origin check plus the
  `SameSite=Strict` cookie.
- Roles: operator tokens run any action; viewer tokens (`--viewer`) only view actions.
- Requests whose `Host` is not in `noray.web.hosts` (plus loopback names and the bind address) are refused (DNS rebinding).

### 5.2 Headers on every response

`Content-Security-Policy: default-src 'none'; script-src 'nonce-…'; style-src 'self'; img-src 'self'; media-src 'self';
connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`, `X-Content-Type-Options: nosniff`,
`Referrer-Policy: no-referrer`, `Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Resource-Policy: same-origin`,
`X-Frame-Options: DENY`, a `Permissions-Policy` denying camera/microphone/geolocation/usb/payment, `Cache-Control: no-store` on
everything authenticated, HSTS with TLS.

### 5.3 Blobs and previews

File content is never inlined into pages. A previewer asks for a **blob URL** with `r.blob(new Blob(name, type, bytes))`:
`/_nr/blob/<token>`, valid for that session for 10 minutes. The type is decided by sniffing the bytes, not by the name or the
caller: only images (png, jpeg, gif, webp, avif), audio and video are served inline; everything else, SVG and HTML included, is
`application/octet-stream` with `Content-Disposition: attachment`. Always with `nosniff` and a `sandbox` CSP. Previews show
HTML and SVG as source text. `Range` requests are supported for media.

### 5.4 Limits

Body ≤ 1 MiB for events and forms (uploads: `noray.web.upload.max.mib`, 64), ≤ 64 sessions and ≤ 16 page views per session
(LRU, idle 10 min), ≤ 4 SSE streams per session, ≤ 512 blob URLs per session, regions slower than 200 ms are logged, pushed
patches over 4 MiB are dropped with a warning, request handling on virtual threads with a cap of 512 concurrent requests.

### 5.5 Public sites

`web.site:/prefix` lets a module serve a public site under that prefix: files (`StaticSite` serves a filesystem subtree with
index files, trailing-slash redirects, ETag, `Range`, MIME by extension, no directory listing, no dot-files) and pages rendered
with the kit but **without** actions (no anonymous server state). Typical use: the static hosting module, a project page.

**Origin isolation.** A hosted site's own JavaScript must never run in the dashboard's origin: it could read dashboard pages
with the operator's cookies, CSRF token included. Every public-site response therefore carries
`Content-Security-Policy: sandbox allow-scripts allow-forms allow-popups allow-downloads`: the site works, but in an opaque
origin, so its requests are cross-site (the `SameSite=Strict` session cookie is never sent) and it cannot read the dashboard's
responses. For sites that need storage or their own origin, `noray.web.site.port` serves public sites on a separate listener
(a different origin) where the sandbox is not needed; the dashboard's listener then serves no site at all.

## 6. Capabilities

| Grant | Gives |
|---|---|
| `web.app` | mount an authenticated app under `/apps/<module id>/` (shown in the dashboard navigation) |
| `web.site:/prefix` | mount a public site under `/prefix` (exact review: it is reachable without login) |
| `service:WebApps` | the unscoped service (mount anything anywhere): core only, never covered by `service:*` |

A sandboxed module asks for `WebApps` and receives a view bound to its id and its granted prefixes. File previews use the
module's own scoped `FileSystemService`, so a preview can never show a file the module could not read.

## 7. `web.kit` — the component library

Every component is a plain Java class or static factory returning `Node` (stateless) or a `Component` (stateful), so adding
one never changes the API of another.

| Area | Components |
|---|---|
| Layout | `AppShell` (brand, navigation of mounted apps, top bar), `Kit.pageHeader`, `card`, `flushCard`, `grid`, `stack`, `row` |
| Content | `badge`, `alert`, `keyValues`, `empty`, `crumbs`, `mono`, `copyButton`, `CodeView` (line numbers, light highlighting), `Markdown`, `timeline`, `tree` |
| Data | `Table<T>` (columns from lambdas, server-side sort, filter, pagination), `LogView` |
| Metrics | `stat` tile (value, unit, footer), `meter`, server-rendered SVG `Charts.line`, `Charts.bar`, `Charts.sparkline` |
| Media | `media` (blurred vignette, zoom on hover), `gallery` |
| Input | `textField`, `passwordField`, `textArea`, `selectField`, `checkbox`, `button` / `submit` (variants), `confirm` |
| Navigation | `Tabs`, `pagination`, `menu`, `dialog` / `dialogButton` / `dialogClose` (native `<dialog>`) |
| Files | `FileBrowser` (folders, previews, optional upload and new folder), `FilePreview` (pluggable `Previewer`s: image, audio, video, PDF notice, Markdown, JSON, CSV, text/code, hex), `StaticSite` |

Accessibility is part of each component's contract: labels are constructor parameters (not optional setters), tables have
headers and captions, charts carry `<title>`/`<desc>` and a data table fallback, colour is never the only signal.

## 8. Testing

- `web.html`: escaping and validation tests including an XSS vector corpus.
- `web.ui`: `UiTester` renders a page, finds elements by text, label or field name, fires actions, applies the patches and
  advances time for timed regions, all in memory. Module authors use it for their own pages.
- `web.server`: real HTTP on an ephemeral loopback port: login, CSRF refusal, headers present, blob content types, limits.
- The node dashboard pages are rendered in tests with fake services, so a broken component fails the build.

## 9. Evolution rules

1. `web.html` and the `Component`/`Render` interfaces are the stable API; they follow the package API level (`noray.api`).
2. The wire protocol and runtime are versioned (`NR-UI/1`); the runtime URL carries its hash, so caches never mix versions.
3. New behaviours, new components and new optional protocol fields are additive. Removing anything needs a new API level.
4. No dependency is added to the web module without the same review as a core dependency.

## 10. Status

Implemented and tested: HTTP tests on a real loopback port, `UiTester` tests, and the dashboard driven in a real browser.
The default theme is *noir* (pure black / pure white, hairlines, square corners, monochrome accents; `noray.web.theme=harbour`
selects the earlier one); the dashboard's *Components* page shows every component in it. `examples/static-hosting` is a
reference module that uses `web.site` and `web.app` from a signed, sandboxed package.

Open: drag-and-drop uploads with progress, histogram and heatmap charts, roles mapped to identity certificates instead of tokens.
