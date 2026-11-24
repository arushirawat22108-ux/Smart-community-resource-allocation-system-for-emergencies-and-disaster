(function () {
  const { esc, api } = CRAS;
  const V = (CRAS.views.queue = { title: "Priority Queue", sub: "Highest priority first. Aging lifts requests that have waited a long time." });
  let filter = "";

  V.init = (root) => {
    root.innerHTML = `
      <div class="card">
        <div class="row" style="margin-bottom:12px"><label class="field fit" style="min-width:220px">Resource<select id="qf"></select></label>
          <div class="fit muted" id="q-meta" style="align-self:center"></div></div>
        <div id="q-table"></div>
        <div class="note"><b>Aging</b> is the bar in the Aging column: bonus points added to the score from waiting time (up to +15). <b>Starving</b> marks requests that have waited more than 4 hours.</div>
      </div>`;
    CRAS.$("#qf").addEventListener("change", (e) => { filter = e.target.value; V.load(); });
    CRAS.$("#q-table").addEventListener("click", act);
  };

  async function act(e) {
    const b = e.target.closest("button[data-act]");
    if (!b) return;
    const id = b.dataset.id, a = b.dataset.act;
    await CRAS.busy(b, async () => {
      if (a === "cancel") await api(`/api/requests/${id}/cancel`, { method: "POST" });
      else await api(`/api/manager/${a}/${id}`, { method: "POST" });
      CRAS.toast(a === "preempt" ? `Request #${id} served by preemption.` : a === "allocate" ? `Request #${id} allocated.` : `Request #${id} cancelled.`, "ok");
      CRAS.updateBadges();
      await V.load();
    });
  }

  V.load = async () => {
    await CRAS.loadRefs();
    const sel = CRAS.$("#qf");
    sel.innerHTML = '<option value="">All resources</option>' + CRAS.state.resources.map((r) => `<option value="${r.id}">${esc(r.name)}</option>`).join("");
    sel.value = filter;
    const mgr = CRAS.isManager();
    let rows = mgr
      ? await api(filter ? `/api/manager/queue/${filter}` : "/api/manager/queue")
      : (await api("/api/requests/pending")).filter((r) => !filter || String(r.resourceId) === filter);
    rows = rows.slice().sort((a, b) => b.priorityScore - a.priorityScore || a.id - b.id);
    CRAS.$("#q-meta").textContent = `${rows.length} waiting`;
    const cols = [
      { h: "#", v: (r, i) => `${i + 1}` },
      { h: "Request", html: (r) => `<b style="font-weight:500">#${r.id}</b> ${esc(r.resourceName)} <span class="faint">× ${r.quantity}</span>` },
      { h: "Sev", html: (r) => CRAS.sev(r.severity) },
      { h: "People", num: 1, v: (r) => r.affectedPeople },
      { h: "Where", v: (r) => r.locationName || "-" },
      { h: "Priority", num: 1, html: (r) => `<b>${r.priorityScore}</b>` },
      { h: "Aging", html: (r) => `<div style="min-width:70px">${CRAS.meter((r.agingBoost / 15) * 100, "")}<span class="hint">+${r.agingBoost}</span></div>` },
      { h: "Waiting", html: (r) => `${CRAS.mins(r.waitingMinutes)} ${r.starving ? CRAS.badge("starving", "bad") : ""}` },
      { h: "Requester", html: (r) => `<span class="faint">${esc(r.requester)}</span>${r.mine ? " " + CRAS.badge("you", "brass") : ""}` }
    ];
    if (mgr) cols.push({ h: "", html: (r) => `<div style="display:flex;gap:6px;white-space:nowrap">
      <button class="btn sm primary" data-act="allocate" data-id="${r.id}">Allocate</button>
      ${r.severity >= 8 ? `<button class="btn sm" data-act="preempt" data-id="${r.id}">Preempt</button>` : ""}
      <button class="btn sm danger" data-act="cancel" data-id="${r.id}">Cancel</button></div>` });
    CRAS.$("#q-table").innerHTML = CRAS.table(cols, rows, { empty: "The queue is empty. Nothing is waiting." });
  };
})();
