(function () {
  const { esc, api } = CRAS;
  const V = (CRAS.views.overview = { title: "Overview", sub: "The depot, the queue and the scheduler at a glance." });
  let map = null, layer = null;

  // Source: os_sim/reports/scheduler_comparison.csv (offline simulation, ~258 requests per run).
  const EVAL = [
    ["FCFS", 151.18, 169.15, 69.55],
    ["Shortest job first", 95.45, 120.65, 73.73],
    ["Static priority", 145.97, 85.51, 76.1],
    ["Earliest deadline first", 149.8, 80.67, 73.31],
    ["CRAS Phase II", 144.89, 85.35, 75.51],
    ["CRAS Phase III", 140.11, 71.55, 76.26],
    ["CRAS Phase III + AI", 141.14, 67.79, 77.36]
  ];

  V.init = (root) => {
    root.innerHTML = `
      <div class="grid g4" id="ov-kpis"></div>
      <div class="grid g-main" style="margin-top:16px">
        <div>
          <div class="card"><h3>Depot stock</h3><p class="muted">Pending demand is what is still waiting in the queue for that resource.</p><div id="ov-res"></div></div>
          <div class="card"><h3>Scheduler evaluation</h3>
            <p class="muted">Offline simulation of eight policies on the same synthetic workload (<code>os_sim/reports</code>). Waits are in minutes.</p>
            <div id="ov-eval"></div>
            <div class="note"><b>Reading it honestly:</b> shortest-job-first has the lowest <i>average</i> wait but ignores urgency. CRAS Phase III + AI has the lowest <i>critical</i> wait, about 60 % below FCFS, while its average stays below FCFS too. The last three rows differ only slightly, and all figures come from simulated data.</div>
          </div>
        </div>
        <div>
          <div class="card"><h3>Live activity</h3><ul class="list feed" id="ov-feed"></ul></div>
          <div class="card"><h3>Locations</h3><div id="ov-map" class="map"></div></div>
        </div>
      </div>`;
    const rows = EVAL.map((r, i) => ({ r, i }));
    const bestCrit = Math.min(...EVAL.map((e) => e[2]));
    CRAS.$("#ov-eval").innerHTML = CRAS.table(
      [
        { h: "Policy", html: ({ r }) => esc(r[0]) },
        { h: "Mean wait", num: 1, v: ({ r }) => r[1].toFixed(1) },
        { h: "Critical wait", num: 1, html: ({ r }) => `<b>${r[2].toFixed(1)}</b>` },
        { h: "Critical on time", num: 1, v: ({ r }) => r[3].toFixed(1) + " %" }
      ],
      rows,
      { rowClass: ({ r }) => (r[2] === bestCrit ? "best" : "") }
    );
    V.onFeed();
  };

  V.onFeed = () => {
    const ul = CRAS.$("#ov-feed");
    if (!ul) return;
    const f = CRAS.state.feed;
    ul.innerHTML = f.length
      ? f.slice(0, 12).map((e) => `<li><time>${CRAS.clock(e.at)}</time>${esc(e.text)}</li>`).join("")
      : '<li class="empty">Waiting for activity. Events appear here the moment they happen.</li>';
  };

  V.load = async () => {
    const [states, pending, auto] = await Promise.all([api("/api/os/states"), api("/api/requests/pending"), api("/api/autopilot")]);
    await CRAS.loadRefs();
    const crit = pending.filter((p) => p.severity >= 8).length;
    const kpi = (v, l, c = "") => `<div class="kpi ${c}"><div class="v">${v}</div><div class="l">${l}</div></div>`;
    CRAS.$("#ov-kpis").innerHTML =
      kpi(states.PENDING ?? 0, "Waiting in queue", states.PENDING ? "warn" : "") +
      kpi(crit, "Critical and waiting", crit ? "bad" : "ok") +
      kpi(states.ALLOCATED ?? 0, "Allocated now") +
      kpi(states.COMPLETED ?? 0, "Completed", "ok");

    const demand = {};
    pending.forEach((p) => (demand[p.resourceId] = (demand[p.resourceId] || 0) + p.quantity));
    CRAS.$("#ov-res").innerHTML = CRAS.state.resources.map((r) => {
      const cap = r.capacity || r.availableQuantity || 1;
      const used = Math.round(100 * (1 - r.availableQuantity / cap));
      const d = demand[r.id] || 0;
      const cover = d === 0 ? "" : d <= r.availableQuantity
        ? `<span class="badge ok">demand covered</span>` : `<span class="badge bad">short by ${d - r.availableQuantity}</span>`;
      return `<div style="margin:14px 0">
        <div style="display:flex;justify-content:space-between;align-items:baseline;gap:10px">
          <b style="font-weight:500">${esc(r.name)}</b>
          <span class="muted"><span class="mono">${r.availableQuantity}</span> / ${cap} ${esc(r.unit)}</span></div>
        ${CRAS.meter(used)}
        <div class="hint" style="margin-top:4px">Pending demand ${d} ${esc(r.unit)} ${cover}</div></div>`;
    }).join("") || '<div class="empty">No resources yet.</div>';

    drawMap();
  };

  function drawMap() {
    const el = CRAS.$("#ov-map");
    if (!el) return;
    if (!window.L) { el.innerHTML = '<div class="empty" style="padding:20px">Map unavailable offline.</div>'; return; }
    const locs = CRAS.state.locations.filter((l) => l.latitude != null && l.longitude != null);
    if (!locs.length) { el.innerHTML = '<div class="empty" style="padding:20px">No locations have coordinates.</div>'; return; }
    if (!map) {
      map = L.map(el, { zoomControl: true, attributionControl: true }).setView([30.2, 78.1], 9);
      L.tileLayer("https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png",
        { maxZoom: 18, attribution: "© OpenStreetMap contributors © CARTO" }).addTo(map);
      layer = L.layerGroup().addTo(map);
    }
    layer.clearLayers();
    const pts = [];
    locs.forEach((l) => {
      const color = l.riskLevel >= 8 ? "#b4574a" : l.riskLevel >= 5 ? "#c9a14a" : "#7f9c70";
      L.circleMarker([l.latitude, l.longitude], { radius: 9, color, fillColor: color, fillOpacity: 0.55, weight: 2 })
        .bindPopup(`<b>${esc(l.name)}</b><br>Risk level ${l.riskLevel} / 10`).addTo(layer);
      pts.push([l.latitude, l.longitude]);
    });
    setTimeout(() => { map.invalidateSize(); map.fitBounds(pts, { padding: [30, 30], maxZoom: 10 }); }, 50);
  }
})();
