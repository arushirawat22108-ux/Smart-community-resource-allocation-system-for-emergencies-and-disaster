/* CRAS core: state, API client, helpers, router, live event stream. */
(function () {
  const C = window.CRAS_CONFIG;
  const S = { token: null, user: null, resources: [], locations: [], current: null, feed: [] };
  const CRAS = (window.CRAS = { cfg: C, state: S, views: {} });

  /* ------------------------------------------------------------ helpers */
  const ESC = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
  CRAS.esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ESC[c]);
  CRAS.$ = (sel, root = document) => root.querySelector(sel);
  CRAS.$$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  CRAS.isManager = () => ["RESOURCE_MANAGER", "ADMIN"].includes(S.user?.role);
  CRAS.isAdmin = () => S.user?.role === "ADMIN";

  CRAS.mins = (m) => {
    m = Math.max(0, Math.round(m || 0));
    if (m < 60) return m + " min";
    const h = Math.floor(m / 60), r = m % 60;
    return h < 48 ? `${h} h ${r} m` : `${Math.floor(h / 24)} d ${h % 24} h`;
  };
  CRAS.clock = (iso) => {
    const d = iso ? new Date(iso) : new Date();
    return isNaN(d) ? "" : d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  };
  CRAS.dt = (iso) => {
    const d = new Date(iso);
    return isNaN(d) ? String(iso || "") : d.toLocaleString([], { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
  };

  const STATUS_CLASS = { PENDING: "warn", ALLOCATED: "info", ACTIVE: "info", COMPLETED: "ok", PREEMPTED: "bad", CANCELLED: "" };
  CRAS.badge = (text, cls) => `<span class="badge ${cls ?? STATUS_CLASS[text] ?? ""}">${CRAS.esc(text)}</span>`;
  CRAS.sev = (n) => `<span class="sev ${n >= 8 ? "hi" : n >= 5 ? "mid" : ""}">${CRAS.esc(n)}</span>`;
  CRAS.meter = (pct, cls) =>
    `<div class="meter ${cls || (pct > 85 ? "bad" : pct > 60 ? "warn" : "ok")}"><i style="width:${Math.max(0, Math.min(100, pct))}%"></i></div>`;
  CRAS.table = (cols, rows, opts = {}) => {
    if (!rows.length) return `<div class="empty">${CRAS.esc(opts.empty || "Nothing to show yet.")}</div>`;
    const head = cols.map((c) => `<th class="${c.num ? "num" : ""}">${CRAS.esc(c.h)}</th>`).join("");
    const body = rows
      .map((r, i) => `<tr class="${opts.rowClass ? opts.rowClass(r, i) : ""}">${cols
        .map((c) => `<td class="${c.num ? "num" : ""}">${c.html ? c.html(r, i) : CRAS.esc(c.v(r, i))}</td>`).join("")}</tr>`)
      .join("");
    return `<div class="tbl-wrap"><table class="data"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
  };

  CRAS.toast = (msg, type = "") => {
    const el = document.createElement("div");
    el.className = "toast " + type;
    el.textContent = msg;
    CRAS.$("#toasts").appendChild(el);
    setTimeout(() => el.remove(), type === "bad" ? 6500 : 3800);
  };

  /** Runs an async action with the button disabled and a spinner; reports errors as toasts. */
  CRAS.busy = async (btn, fn) => {
    const label = btn ? btn.innerHTML : "";
    if (btn) { btn.disabled = true; btn.innerHTML = `<span class="spin"></span>${label}`; }
    try { return await fn(); }
    catch (e) { CRAS.toast(e.message, "bad"); }
    finally { if (btn) { btn.disabled = false; btn.innerHTML = label; } }
  };

  CRAS.tabs = (root, tabs, render) => {
    root.innerHTML = `<div class="tabs">${tabs.map((t, i) => `<button class="tab ${i ? "" : "active"}" data-t="${t.id}">${CRAS.esc(t.label)}</button>`).join("")}</div><div class="tab-body"></div>`;
    const body = CRAS.$(".tab-body", root);
    const open = (id) => {
      CRAS.$$(".tab", root).forEach((b) => b.classList.toggle("active", b.dataset.t === id));
      body.innerHTML = "";
      render(id, body);
    };
    root.addEventListener("click", (e) => { const b = e.target.closest(".tab"); if (b && root.contains(b)) open(b.dataset.t); });
    open(tabs[0].id);
    return { open };
  };

  /* ------------------------------------------------------------ API */
  CRAS.api = async (path, { method = "GET", body, headers = {}, auth = true } = {}) => {
    const h = { ...headers };
    if (body !== undefined) h["Content-Type"] = "application/json";
    if (auth && S.token) h.Authorization = "Bearer " + S.token;
    let res;
    try {
      res = await fetch(C.API_URL + path, { method, headers: h, body: body !== undefined ? JSON.stringify(body) : undefined });
    } catch (e) {
      const err = new Error(`Cannot reach the CRAS server at ${C.API_URL}. Is the backend running?`);
      err.network = true;
      throw err;
    }
    const text = await res.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch { data = text; } }
    if (!res.ok) {
      const msg = (data && data.message) || (typeof data === "string" && data) || `Request failed (${res.status})`;
      const err = new Error(msg);
      err.status = res.status;
      err.data = data;
      if (res.status === 401 && auth && S.token && !path.startsWith("/api/auth/")) CRAS.logout("Your session expired. Please sign in again.");
      throw err;
    }
    return data;
  };

  CRAS.loadRefs = async () => {
    const [r, l] = await Promise.all([CRAS.api("/api/resources"), CRAS.api("/api/locations")]);
    S.resources = r;
    S.locations = l;
  };

  /* ------------------------------------------------------------ navigation */
  const MGR = ["RESOURCE_MANAGER", "ADMIN"];
  const NAV = [
    { group: "Workspace" },
    { id: "overview", label: "Overview" },
    { id: "request", label: "Request Help" },
    { id: "queue", label: "Priority Queue", badge: true },
    { group: "Operations", roles: MGR },
    { id: "manager", label: "Manager Desk", roles: MGR },
    { group: "Systems Lab" },
    { id: "oslab", label: "OS Lab" },
    { id: "dbms", label: "DBMS Lab", roles: MGR },
    { id: "oracle", label: "Risk Oracle" },
    { group: "Governance", roles: ["ADMIN"] },
    { id: "audit", label: "Audit Ledger", roles: ["ADMIN"] }
  ];
  const allowed = (item) => !item.roles || item.roles.includes(S.user.role);

  CRAS.buildNav = () => {
    const nav = CRAS.$("#nav");
    nav.innerHTML = NAV.filter(allowed).map((n) => n.group
      ? `<div class="nav-group">${n.group}</div>`
      : `<button class="nav-item" data-view="${n.id}"><span>${n.label}</span>${n.badge ? '<span class="count" id="badge-pending"></span>' : ""}</button>`).join("");
    nav.onclick = (e) => { const b = e.target.closest(".nav-item"); if (b) CRAS.go(b.dataset.view); };
  };

  const inited = new Set();
  CRAS.go = async (id) => {
    const item = NAV.find((n) => n.id === id);
    const view = CRAS.views[id];
    if (!item || !view || !allowed(item)) id = "overview";
    const v = CRAS.views[id];
    S.current = id;
    location.hash = id;
    CRAS.$$(".view").forEach((s) => (s.hidden = s.id !== "v-" + id));
    CRAS.$$(".nav-item").forEach((b) => b.classList.toggle("active", b.dataset.view === id));
    CRAS.$("#page-title").textContent = v.title;
    CRAS.$("#page-sub").textContent = v.sub || "";
    const root = CRAS.$("#v-" + id);
    if (!inited.has(id)) { inited.add(id); v.init && v.init(root); }
    try { await (v.load && v.load(root)); } catch (e) { CRAS.toast(e.message, "bad"); }
  };

  CRAS.updateBadges = async () => {
    try {
      const p = await CRAS.api("/api/requests/pending");
      const el = CRAS.$("#badge-pending");
      if (el) el.textContent = p.length ? p.length : "";
    } catch { /* ignore */ }
  };

  /* ------------------------------------------------------------ live events (SSE over fetch) */
  const DESCRIBE = {
    REQUEST_CREATED: (p) => `New request #${p.id} (severity ${p.severity})`,
    REQUEST_CANCELLED: (p) => `Request #${p.id} cancelled`,
    REQUEST_COMPLETED: (p) => `Request #${p} completed`,
    ALLOCATION_UPDATED: (p) => `Allocation #${p} recorded`,
    PREEMPTION: (p) => `Critical request #${p} preempted a lower-priority allocation`,
    STOCK_CHANGED: () => "Stock level changed",
    AUTOPILOT: (p) => `Autopilot ${p.enabled ? "engaged" : "disengaged"}`,
    AUTOPILOT_TICK: (p) => `Autopilot tick ${p.tick}: ${p.dispatched} dispatched, ${p.preempted} preempted`,
    BANKER_UPDATED: (p) => p.reset ? "Banker scenario reset" : p.released ? `${p.camp} released its holdings`
      : `${p.camp}: Banker request ${p.granted ? "granted" : "denied"}`
  };
  let refreshTimer = null;

  function onEvent(name, data) {
    if (name === "ping" || name === "hello") {
      CRAS.$("#live-text").textContent = `live · ${data.payload?.clients ?? 1} connected`;
      return;
    }
    const p = data.payload;
    const quiet = name === "AUTOPILOT_TICK" && !p.dispatched && !p.preempted;
    const text = (DESCRIBE[name] || (() => name))(p ?? {});
    S.feed.unshift({ at: data.ts, name, text });
    S.feed.length = Math.min(S.feed.length, 40);
    CRAS.views.overview?.onFeed?.();
    if (quiet) return;
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(() => {
      CRAS.updateBadges();
      const v = CRAS.views[S.current];
      if (v && v.live !== false) Promise.resolve(v.load && v.load(CRAS.$("#v-" + S.current))).catch(() => {});
    }, 700);
  }

  function setLive(on) {
    CRAS.$("#live-dot").className = "dot " + (on ? "on" : "off");
    CRAS.$("#live-text").textContent = on ? "live" : "reconnecting…";
  }

  CRAS.connectEvents = async () => {
    CRAS.disconnectEvents();
    const ctrl = new AbortController();
    S.sse = ctrl;
    try {
      const res = await fetch(C.API_URL + "/api/events/stream", {
        headers: { Authorization: "Bearer " + S.token, Accept: "text/event-stream" },
        signal: ctrl.signal
      });
      if (!res.ok) throw new Error("status " + res.status);
      setLive(true);
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      let buf = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true }).replace(/\r\n/g, "\n");
        let i;
        while ((i = buf.indexOf("\n\n")) >= 0) {
          const block = buf.slice(0, i);
          buf = buf.slice(i + 2);
          let name = "message", data = "";
          for (const line of block.split("\n")) {
            if (line.startsWith("event:")) name = line.slice(6).trim();
            else if (line.startsWith("data:")) data += line.slice(5).trim();
          }
          if (data) { try { onEvent(name, JSON.parse(data)); } catch { /* ignore malformed */ } }
        }
      }
    } catch (e) { if (ctrl.signal.aborted) return; }
    if (ctrl.signal.aborted) return;
    setLive(false);
    if (S.token) setTimeout(() => { if (S.sse === ctrl) CRAS.connectEvents(); }, 4000);
  };
  CRAS.disconnectEvents = () => { if (S.sse) { S.sse.abort(); S.sse = null; } };

  /* ------------------------------------------------------------ session */
  CRAS.logout = (message) => {
    CRAS.disconnectEvents();
    S.token = null; S.user = null; S.feed = [];
    sessionStorage.removeItem("cras.token");
    CRAS.$("#app-view").hidden = true;
    CRAS.$("#login-view").hidden = false;
    CRAS.$("#login-password").value = "";
    CRAS.$("#login-error").textContent = message || "";
  };

  CRAS.enter = async () => {
    CRAS.$("#login-view").hidden = true;
    CRAS.$("#app-view").hidden = false;
    CRAS.$("#who").innerHTML = `<b>${CRAS.esc(S.user.fullName)}</b><span>${CRAS.esc(S.user.role.replace("_", " ").toLowerCase())}</span>`;
    CRAS.buildNav();
    try { await CRAS.loadRefs(); } catch (e) { CRAS.toast(e.message, "bad"); }
    CRAS.updateBadges();
    CRAS.connectEvents();
    await CRAS.go((location.hash || "").slice(1) || "overview");
  };
})();
