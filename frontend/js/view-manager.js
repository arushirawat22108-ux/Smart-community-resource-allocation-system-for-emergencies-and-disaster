(function () {
  const { esc, api } = CRAS;
  const V = (CRAS.views.manager = { title: "Manager Desk", sub: "Dispatch work, manage stock and run the scheduler autonomously." });

  V.init = (root) => {
    root.innerHTML = `
      <div class="grid g2">
        <div>
          <div class="card"><h3>Autopilot</h3>
            <p class="muted">The scheduler as a background daemon: on every tick it dispatches the best waiting request for each resource, and can preempt for critical ones. It runs on its own thread, competing with your clicks for the same locked rows.</p>
            <div id="ap-box"></div></div>
          <div class="card"><h3>Dispatch</h3>
            <p class="muted">Serve the head of one resource's queue (priority, aging, backfilling and critical reservation apply).</p>
            <div id="dp-box" class="row"></div></div>
        </div>
        <div>
          <div class="card"><h3>Stock</h3><div id="st-box"></div>
            <h4>Add a resource</h4>
            <form id="nr-form" class="row" novalidate>
              <label class="field">Name<input id="nr-name" required></label>
              <label class="field">Unit<input id="nr-unit" placeholder="litres" required></label>
              <label class="field">Opening stock<input id="nr-qty" type="number" min="0" value="100"></label>
              <button class="btn fit" type="submit">Add</button></form></div>
        </div>
      </div>
      <div class="card"><h3>Allocations</h3><div id="al-box"></div></div>`;
    root.addEventListener("click", click);
    CRAS.$("#nr-form").addEventListener("submit", addResource);
  };

  async function addResource(e) {
    e.preventDefault();
    const name = CRAS.$("#nr-name").value.trim(), unit = CRAS.$("#nr-unit").value.trim();
    if (!name || !unit) { CRAS.toast("Name and unit are required.", "warn"); return; }
    await CRAS.busy(e.submitter, async () => {
      await api("/api/resources", { method: "POST", body: { name, unit, availableQuantity: Number(CRAS.$("#nr-qty").value) || 0 } });
      CRAS.toast(`${name} added.`, "ok");
      e.target.reset();
      await V.load();
    });
  }

  async function click(e) {
    const b = e.target.closest("button[data-act]");
    if (!b) return;
    const a = b.dataset.act, id = b.dataset.id;
    await CRAS.busy(b, async () => {
      if (a === "dispatch") {
        const r = await api(`/api/manager/dispatch/${id}`, { method: "POST" });
        CRAS.toast(`${r.result}${r.requestId ? " · request #" + r.requestId : ""}`, r.allocationId ? "ok" : "warn");
      } else if (a === "complete") {
        await api(`/api/manager/complete/${id}`, { method: "POST" });
        CRAS.toast(`Request #${id} completed.`, "ok");
      } else if (a === "restock" || a === "write-off") {
        const inp = CRAS.$(`#delta-${id}`);
        const n = Math.abs(parseInt(inp.value, 10) || 0);
        if (!n) { CRAS.toast("Enter a quantity first.", "warn"); return; }
        await api(`/api/resources/${id}/restock?delta=${a === "restock" ? n : -n}`, { method: "POST" });
        CRAS.toast(`Stock ${a === "restock" ? "increased" : "reduced"} by ${n}.`, "ok");
      } else if (a === "ap-toggle") {
        const on = b.dataset.on === "1";
        await api(`/api/autopilot/configure?enabled=${on}&preemption=${CRAS.$("#ap-pre")?.checked ?? true}`, { method: "POST" });
      } else if (a === "ap-step") {
        const r = await api("/api/autopilot/step", { method: "POST" });
        CRAS.toast(`Tick ${r.tick}: ${r.dispatched} dispatched, ${r.preempted} preempted.`, r.dispatched || r.preempted ? "ok" : "");
      }
      CRAS.updateBadges();
      await V.load();
    });
  }

  V.load = async () => {
    await CRAS.loadRefs();
    const [ap, al] = await Promise.all([api("/api/autopilot"), api("/api/manager/allocations")]);

    CRAS.$("#ap-box").innerHTML = `
      <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">
        ${CRAS.badge(ap.enabled ? "engaged" : "off", ap.enabled ? "ok" : "")}
        <button class="btn sm ${ap.enabled ? "danger" : "primary"}" data-act="ap-toggle" data-on="${ap.enabled ? 0 : 1}">${ap.enabled ? "Disengage" : "Engage"}</button>
        <button class="btn sm" data-act="ap-step">Run one tick</button>
        <label class="hint" style="display:flex;gap:6px;align-items:center"><input type="checkbox" id="ap-pre" ${ap.preemption ? "checked" : ""}>allow preemption</label>
      </div>
      <p class="hint" style="margin-top:10px">${ap.ticks} ticks · ${ap.dispatched} dispatched · ${ap.preemptions} preemptions</p>
      <ul class="list feed" style="max-height:170px;overflow:auto">${ap.journal.length ? ap.journal.slice(0, 12).map((j) => `<li>${esc(j)}</li>`).join("") : '<li class="empty">No activity yet.</li>'}</ul>`;

    CRAS.$("#dp-box").innerHTML = CRAS.state.resources.map((r) => `<button class="btn" data-act="dispatch" data-id="${r.id}" style="flex:1 1 140px">${esc(r.name)}</button>`).join("");

    CRAS.$("#st-box").innerHTML = CRAS.table([
      { h: "Resource", html: (r) => `<b style="font-weight:500">${esc(r.name)}</b>` },
      { h: "Stock", html: (r) => `<span class="mono">${r.availableQuantity}</span><span class="faint"> / ${r.capacity || r.availableQuantity} ${esc(r.unit)}</span>` },
      { h: "Adjust", html: (r) => `<div style="display:flex;gap:5px;min-width:200px"><input id="delta-${r.id}" type="number" min="1" placeholder="qty" style="width:80px;padding:5px 8px">
        <button class="btn sm" data-act="restock" data-id="${r.id}">+ Add</button><button class="btn sm danger" data-act="write-off" data-id="${r.id}">− Remove</button></div>` }
    ], CRAS.state.resources);

    CRAS.$("#al-box").innerHTML = CRAS.table([
      { h: "#", v: (a) => a.id },
      { h: "Request", html: (a) => `#${a.requestId} · ${esc(a.resource)} <span class="faint">× ${a.quantity} ${esc(a.unit)}</span>` },
      { h: "Sev", html: (a) => CRAS.sev(a.severity) },
      { h: "Requester", html: (a) => `<span class="faint">${esc(a.requester)}</span>` },
      { h: "When", v: (a) => CRAS.dt(a.allocatedAt) },
      { h: "Status", html: (a) => CRAS.badge(a.status) },
      { h: "", html: (a) => a.status === "ACTIVE" ? `<button class="btn sm" data-act="complete" data-id="${a.requestId}">Mark complete</button>` : "" }
    ], al, { empty: "No allocations yet." });
  };
})();
