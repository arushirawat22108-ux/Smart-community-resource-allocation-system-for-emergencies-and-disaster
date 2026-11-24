(function () {
  const { esc, api } = CRAS;
  const V = (CRAS.views.request = { title: "Request Help", sub: "Ask for relief supplies. Your request joins the priority queue straight away.", live: false });
  let idemKey = null;
  const newKey = () => (crypto.randomUUID ? crypto.randomUUID() : "k-" + Date.now() + "-" + Math.random().toString(16).slice(2));

  V.init = (root) => {
    root.innerHTML = `
      <div class="grid g-main">
        <div class="card"><h3>New request</h3>
          <form id="rq-form" novalidate>
            <div class="row">
              <label class="field">Resource<select id="rq-res"></select></label>
              <label class="field">Location<select id="rq-loc"></select></label>
            </div>
            <div class="row" style="margin-top:12px">
              <label class="field">Quantity<input id="rq-qty" type="number" min="1" value="50"></label>
              <label class="field">People affected<input id="rq-ppl" type="number" min="1" value="25"></label>
            </div>
            <p class="hint" style="margin-top:14px">Tick anything that applies. Severity is estimated for you from the location, stock levels and your answers.</p>
            <label style="display:flex;gap:8px;margin:.4rem 0;font-size:.9rem"><input type="checkbox" id="rq-med">Someone needs urgent medical help</label>
            <label style="display:flex;gap:8px;margin:.4rem 0;font-size:.9rem"><input type="checkbox" id="rq-vul">Infants, elderly or pregnant people are among those affected</label>
            <label style="display:flex;gap:8px;margin:.4rem 0;font-size:.9rem"><input type="checkbox" id="rq-water">No clean water for a day or more</label>
            <div class="note" id="rq-est">Estimating severity…</div>
            <p class="hint" id="rq-hint"></p>
            <button class="btn primary" id="rq-submit" type="submit" style="margin-top:6px">Submit request</button>
            <div id="rq-result"></div>
          </form>
          <div class="note"><b>Safe to retry.</b> Each form carries a one-time Idempotency-Key, so a double click or a network retry creates the request once.</div>
        </div>
        <div class="card"><h3>How it is prioritised</h3>
          <p class="muted">Score 0-100 from severity, people affected, location risk, live stock scarcity and how long the request has waited (aging). Waiting always raises priority, so nothing starves.</p>
          <p class="muted">Critical requests (severity 8-10) may preempt a lower-priority allocation when stock runs out.</p></div>
      </div>
      <div class="card"><h3>My requests</h3><div id="rq-mine"></div></div>`;
    CRAS.$("#rq-res").addEventListener("change", hint);
    CRAS.$("#rq-form").addEventListener("input", scheduleEstimate);
    CRAS.$("#rq-form").addEventListener("change", scheduleEstimate);
    CRAS.$("#rq-form").addEventListener("submit", submit);
    CRAS.$("#rq-mine").addEventListener("click", mineClick);
  };

  function hint() {
    const r = CRAS.state.resources.find((x) => String(x.id) === CRAS.$("#rq-res").value);
    CRAS.$("#rq-hint").textContent = r ? `In stock now: ${r.availableQuantity} ${r.unit}.` : "";
  }

  function payload() {
    return {
      resourceId: Number(CRAS.$("#rq-res").value),
      locationId: CRAS.$("#rq-loc").value ? Number(CRAS.$("#rq-loc").value) : null,
      quantity: Number(CRAS.$("#rq-qty").value),
      affectedPeople: Number(CRAS.$("#rq-ppl").value),
      medicalNeed: CRAS.$("#rq-med").checked,
      vulnerablePeople: CRAS.$("#rq-vul").checked,
      noCleanWater: CRAS.$("#rq-water").checked
    };
  }

  let estTimer = null;
  function scheduleEstimate() { clearTimeout(estTimer); estTimer = setTimeout(estimate, 250); }
  async function estimate() {
    const body = payload();
    const box = CRAS.$("#rq-est");
    if (!body.resourceId || !(body.quantity >= 1) || !(body.affectedPeople >= 1)) { box.textContent = "Enter a quantity and the number of people to see the estimated severity."; return; }
    try {
      const e = await api("/api/requests/estimate", { method: "POST", body });
      box.innerHTML = `<b>Estimated severity: ${e.severity} / 10</b>${e.severity >= 8 ? " " + CRAS.badge("critical", "bad") : ""}<br><span class="hint">${e.reasons.map(esc).join("<br>")}</span>`;
    } catch (err) { box.textContent = "Could not estimate severity: " + err.message; }
  }

  async function submit(e) {
    e.preventDefault();
    const btn = CRAS.$("#rq-submit");
    const body = payload();
    if (!(body.quantity >= 1) || !(body.affectedPeople >= 1)) { CRAS.toast("Quantity and people affected must be at least 1.", "warn"); return; }
    await CRAS.busy(btn, async () => {
      const r = await api("/api/requests", { method: "POST", body, headers: { "Idempotency-Key": idemKey } });
      idemKey = newKey();
      CRAS.$("#rq-result").innerHTML = `<div class="banner ok">Request <b>#${r.id}</b> received. Priority score <b>${r.priorityScore}</b>, severity <b>${r.severity}</b>, status ${esc(r.status)}.</div>`;
      CRAS.updateBadges();
      await loadMine();
    });
  }

  async function loadMine() {
    const rows = (await api("/api/requests")).filter((r) => r.mine || CRAS.isManager());
    const mine = CRAS.isManager() ? rows.filter((r) => r.mine) : rows;
    CRAS.$("#rq-mine").innerHTML = CRAS.table([
      { h: "#", v: (r) => r.id },
      { h: "Resource", html: (r) => `${esc(r.resourceName)} <span class="faint">× ${r.quantity}</span>` },
      { h: "Severity", html: (r) => CRAS.sev(r.severity) },
      { h: "Priority", num: 1, v: (r) => r.priorityScore },
      { h: "Waiting", v: (r) => CRAS.mins(r.waitingMinutes) },
      { h: "Status", html: (r) => CRAS.badge(r.status) },
      { h: "", html: (r) => `<button class="btn sm ghost" data-act="tl" data-id="${r.id}">History</button> ${r.status === "PENDING" ? `<button class="btn sm danger" data-act="cancel" data-id="${r.id}">Cancel</button>` : ""}` }
    ], mine, { empty: "You have not made any requests yet." });
  }

  async function mineClick(e) {
    const b = e.target.closest("button[data-act]");
    if (!b) return;
    const id = b.dataset.id;
    if (b.dataset.act === "cancel") {
      await CRAS.busy(b, async () => { await api(`/api/requests/${id}/cancel`, { method: "POST" }); CRAS.toast(`Request #${id} cancelled.`, "ok"); CRAS.updateBadges(); await loadMine(); });
    } else {
      await CRAS.busy(b, async () => {
        const ev = await api(`/api/requests/${id}/timeline`);
        const tr = b.closest("tr");
        const next = tr.nextElementSibling;
        if (next && next.classList.contains("tl-row")) { next.remove(); return; }
        const html = ev.length ? ev.map((x) => `${x.fromStatus ? esc(x.fromStatus) + " → " : "created as "}<b>${esc(x.toStatus)}</b> <span class="faint">${esc(x.at)}</span>`).join("<br>") : "No history recorded.";
        tr.insertAdjacentHTML("afterend", `<tr class="tl-row"><td colspan="7" class="muted" style="background:var(--surface2)">${html}</td></tr>`);
      });
    }
  }

  V.load = async () => {
    await CRAS.loadRefs();
    if (!idemKey) idemKey = newKey();
    const keep = CRAS.$("#rq-res").value;
    CRAS.$("#rq-res").innerHTML = CRAS.state.resources.map((r) => `<option value="${r.id}">${esc(r.name)} (${esc(r.unit)})</option>`).join("");
    if (keep) CRAS.$("#rq-res").value = keep;
    CRAS.$("#rq-loc").innerHTML = '<option value="">Not specified</option>' + CRAS.state.locations.map((l) => `<option value="${l.id}">${esc(l.name)} · risk ${l.riskLevel}</option>`).join("");
    hint();
    await loadMine();
    estimate();
  };
})();
