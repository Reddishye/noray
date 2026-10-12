/* Noray UI runtime, protocol NR-UI/1. Hand-written, no dependencies, no build step.
 * It only ever sends "this element's action happened" (+ its value or form fields) and applies server-rendered patches. */
(() => {
  "use strict";
  const body = document.body;
  const page = body.dataset.nrPage;
  const csrf = body.dataset.nrCsrf;
  if (!page) return;

  // ── sending events ────────────────────────────────────────────────────────
  let inflight = 0;
  async function send(token, type, el, form) {
    const msg = { v: 1, page, token, type, value: valueOf(el) };
    if (form) msg.form = fields(form, el);
    inflight++;
    body.classList.add("nr-busy");
    try {
      const res = await fetch("/_nr/event", {
        method: "POST", credentials: "same-origin", cache: "no-store",
        headers: { "Content-Type": "application/json", "X-NR-CSRF": csrf },
        body: JSON.stringify(msg)
      });
      if (res.status === 410 || res.status === 401) { location.reload(); return; }
      if (!res.ok) { toast({ level: "ERROR", message: (await res.text()) || ("Request failed (" + res.status + ")") }); return; }
      apply(await res.json());
    } catch (e) {
      toast({ level: "ERROR", message: "Connection lost" });
    } finally {
      if (--inflight === 0) body.classList.remove("nr-busy");
    }
  }

  // File-upload forms go as multipart to the form's own action (it carries the token); the answer is the usual result.
  async function upload(form, submitter) {
    const fd = new FormData(form, submitter instanceof HTMLButtonElement ? submitter : undefined);
    inflight++;
    body.classList.add("nr-busy");
    try {
      const res = await fetch(form.getAttribute("action"), {
        method: "POST", credentials: "same-origin", cache: "no-store", headers: { "X-NR-CSRF": csrf }, body: fd
      });
      if (res.status === 413) { toast({ level: "ERROR", message: "File too large" }); return; }
      if (res.status === 401) { location.reload(); return; }
      if (!res.ok) { toast({ level: "ERROR", message: (await res.text()) || ("Upload failed (" + res.status + ")") }); return; }
      apply(await res.json());
      form.reset();
    } catch (e) {
      toast({ level: "ERROR", message: "Connection lost" });
    } finally {
      if (--inflight === 0) body.classList.remove("nr-busy");
    }
  }

  function valueOf(el) {
    if (!el) return "";
    if (el.type === "checkbox" || el.type === "radio") return el.checked ? (el.value || "on") : "";
    return el.value !== undefined && el.value !== null ? String(el.value) : (el.dataset.nrValue || "");
  }

  function fields(form, submitter) {
    const out = {};
    const fd = new FormData(form, submitter instanceof HTMLButtonElement ? submitter : undefined);
    for (const [k, v] of fd.entries()) {
      if (typeof v !== "string") continue; // files go through uploads, not events
      (out[k] = out[k] || []).push(v);
    }
    return out;
  }

  function tokenFor(el, type) {
    const spec = el.getAttribute("data-nr-on");
    if (!spec) return null;
    for (const part of spec.split(" ")) {
      const i = part.indexOf(":");
      if (part.slice(0, i) === type) return part.slice(i + 1);
    }
    return null;
  }

  function confirmed(el) {
    const host = el.closest("[data-nr-confirm]");
    return !host || window.confirm(host.getAttribute("data-nr-confirm"));
  }

  const timers = new WeakMap();
  function handle(type, ev) {
    const el = ev.target instanceof Element ? ev.target.closest("[data-nr-on]") : null;
    if (!el) return;
    const token = tokenFor(el, type);
    if (!token) return;
    if (type === "submit") {
      ev.preventDefault();
      if (!confirmed(ev.submitter || el)) return;
      if ((el.getAttribute("enctype") || "").toLowerCase() === "multipart/form-data") upload(el, ev.submitter);
      else send(token, type, ev.submitter || null, el);
      return;
    }
    if (type === "click") {
      if (el.tagName === "A" || el.type === "submit") ev.preventDefault();
      if (!confirmed(el)) return;
    }
    if (type === "input") { // debounce typing
      clearTimeout(timers.get(el));
      timers.set(el, setTimeout(() => send(token, type, el), 250));
      return;
    }
    send(token, type, el);
  }
  for (const t of ["click", "change", "input", "submit"]) document.addEventListener(t, ev => handle(t, ev), true);
  document.addEventListener("toggle", ev => handle("toggle", ev), true);

  // ── applying patches ──────────────────────────────────────────────────────
  function apply(r) {
    if (!r || r.v !== 1) return;
    if (r.navigate) { location.assign(r.navigate); return; }
    for (const p of r.patches || []) patch(p);
    for (const t of r.toasts || []) toast(t);
  }

  function patch(p) {
    const old = document.querySelector('[data-nr-region="' + CSS.escape(p.region) + '"]');
    if (!old) return;
    const tpl = document.createElement("template");
    tpl.innerHTML = p.html; // server-rendered from the escaped node tree
    const next = tpl.content.firstElementChild;
    if (next) morph(old, next);
    behaviours(old);
  }

  // Keeps elements that are the same (tag + id/key), so focus, caret, scroll and open <details> survive a re-render.
  function morph(a, b) {
    if (a.nodeType !== b.nodeType || a.nodeName !== b.nodeName || key(a) !== key(b)) {
      a.replaceWith(b.cloneNode(true));
      return;
    }
    if (a.nodeType === Node.TEXT_NODE || a.nodeType === Node.COMMENT_NODE) {
      if (a.nodeValue !== b.nodeValue) a.nodeValue = b.nodeValue;
      return;
    }
    syncAttributes(a, b);
    if (a.tagName === "TEXTAREA") { if (document.activeElement !== a) a.value = b.textContent; return; }
    morphChildren(a, b);
  }

  // Invariant: after step i, a.childNodes[0..i] mirror b's first i+1 children; unmatched old nodes drift to the end.
  function morphChildren(a, b) {
    const newKids = Array.from(b.childNodes);
    const keyed = new Map();
    for (const k of Array.from(a.childNodes)) { const kk = key(k); if (kk) keyed.set(kk, k); }
    newKids.forEach((nk, i) => {
      const cur = a.childNodes[i] || null;
      const kk = key(nk);
      let match = null;
      if (kk) match = keyed.get(kk) || null;
      else if (cur && !key(cur) && cur.nodeName === nk.nodeName) match = cur;
      if (match) {
        if (kk) keyed.delete(kk);
        if (match !== cur) a.insertBefore(match, cur);
        morph(match, nk);
      } else {
        a.insertBefore(nk.cloneNode(true), cur);
      }
    });
    while (a.childNodes.length > newKids.length) a.removeChild(a.lastChild);
  }

  function key(n) {
    if (n.nodeType !== Node.ELEMENT_NODE) return null;
    return n.id || n.getAttribute("data-nr-key") || n.getAttribute("data-nr-region") || null;
  }

  function syncAttributes(a, b) {
    const editing = document.activeElement === a;
    for (const at of Array.from(a.attributes)) if (!b.hasAttribute(at.name)) a.removeAttribute(at.name);
    for (const at of Array.from(b.attributes)) {
      if (a.getAttribute(at.name) !== at.value) a.setAttribute(at.name, at.value);
    }
    if (a.tagName === "INPUT") {
      if (a.type === "checkbox" || a.type === "radio") a.checked = b.hasAttribute("checked");
      else if (!editing) a.value = b.getAttribute("value") || "";
    }
    if (a.tagName === "SELECT" && !editing) {
      const sel = b.querySelector("option[selected]");
      if (sel) a.value = sel.value;
    }
    if (a.tagName === "DETAILS") a.open = b.hasAttribute("open");
  }

  // ── toasts ────────────────────────────────────────────────────────────────
  function toast(t) {
    let host = document.querySelector(".nr-toasts");
    if (!host) {
      host = document.createElement("div");
      host.className = "nr-toasts";
      host.setAttribute("role", "status");
      host.setAttribute("aria-live", "polite");
      body.appendChild(host);
    }
    const el = document.createElement("div");
    el.className = "nr-toast nr-toast-" + (t.level || "INFO");
    el.textContent = t.message || "";
    host.appendChild(el);
    setTimeout(() => el.remove(), t.level === "ERROR" ? 8000 : 4000);
  }

  // ── behaviours (a closed set; adding one is a protocol change) ────────────
  document.addEventListener("click", ev => {
    const el = ev.target instanceof Element ? ev.target.closest("[data-nr-b]") : null;
    if (!el) return;
    const b = el.getAttribute("data-nr-b");
    if (b === "copy") {
      navigator.clipboard && navigator.clipboard.writeText(el.getAttribute("data-nr-copy") || "")
        .then(() => toast({ level: "SUCCESS", message: "Copied" }));
    } else if (b === "dialog-open") {
      const d = document.getElementById(el.getAttribute("data-nr-target"));
      if (d && d.showModal) d.showModal();
    } else if (b === "dialog-close") {
      const d = el.closest("dialog");
      if (d) d.close();
    } else if (b === "theme") {
      const root = document.documentElement;
      const dark = root.dataset.theme ? root.dataset.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
      root.dataset.theme = dark ? "light" : "dark";
      document.cookie = "nr_theme=" + root.dataset.theme + "; Path=/; Max-Age=31536000; SameSite=Strict";
    }
  });

  function behaviours(root) {
    const f = root.querySelector("[data-nr-b~='autofocus']");
    if (f && f.focus) f.focus();
  }
  behaviours(document);

  // ── server push ───────────────────────────────────────────────────────────
  if (body.dataset.nrStream === "1" && window.EventSource) {
    let es;
    const open = () => {
      es = new EventSource("/_nr/stream?page=" + encodeURIComponent(page));
      es.addEventListener("patch", e => apply({ v: 1, patches: [JSON.parse(e.data)] }));
      es.addEventListener("gone", () => { es.close(); location.reload(); });
    };
    open();
    window.addEventListener("pagehide", () => es && es.close());
  }
})();
