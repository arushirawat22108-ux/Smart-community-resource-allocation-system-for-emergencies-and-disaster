(function () {
  const { esc, api } = CRAS;
  const V = (CRAS.views.oslab = {
    title: "OS Lab",
    sub: "Operating-systems ideas running on real data: scheduling, deadlock avoidance, process states.",
    live: false
  });
  let tabs;

  V.init = (root) => { tabs = CRAS.tabs(root, [{ id: "sim", label: "Scheduler simulator" }, { id: "banker", label: "Banker's algorithm" }, { id: "states", label: "Process states" }], render); };
  let first = true;
  V.load = async () => {                       // tabs render themselves once on init; re-open on every later visit
    if (first) { first = false; return; }
    tabs.open(CRAS.$(".tab.active", CRAS.$("#v-oslab"))?.dataset.t || "sim");
  };

  function render(id, body) { ({ sim, banker, states })[id](body); }

  /* ================================================================ scheduler simulator */
  function sim(body) {
    body.innerHTML = `
      <div class="card"><h3>Same workload, six scheduling policies</h3>
        <p class="muted">Each emergency request is treated as a process (1 tick = 1 minute). The simulator replays one workload under FCFS, SJF, SRTF, static priority, round robin and CRAS (priority + aging) so the trade-offs are visible side by side.</p>
        <form id="sm-form" class="row" novalidate>
          <label class="field">Workload<select id="sm-src"><option value="sample">Mixed burst (convoy effect)</option><option value="stream">Steady urgent stream (starvation test)</option><option value="live">Live pending queue</option></select></label>
          <label class="field">Jobs<input id="sm-n" type="number" min="4" max="30" value="14"></label>
          <label class="field">Seed<input id="sm-seed" type="number" value="7"></label>
          <label class="field">RR quantum<input id="sm-q" type="number" min="1" max="30" value="4"></label>
          <label class="field">Aging step (min per +1)<input id="sm-a" type="number" min="1" max="120" value="5"></label>
          <label class="field">Starved if wait &gt;<input id="sm-s" type="number" min="5" value="60"></label>
          <button class="btn primary fit" type="submit">Run</button></form></div>
      <div id="sm-out"></div>`;
    CRAS.$("#sm-form").addEventListener("submit", (e) => { e.preventDefault(); run(e.submitter); });
    CRAS.$("#sm-src").addEventListener("change", (e) => {      // sensible defaults per workload
      const d = { sample: [14, 5, 60], stream: [20, 2, 30], live: [14, 5, 60] }[e.target.value];
      CRAS.$("#sm-n").value = d[0]; CRAS.$("#sm-a").value = d[1]; CRAS.$("#sm-s").value = d[2];
      run();
    });
    run();
  }

  async function run(btn) {
    const q = new URLSearchParams({ source: CRAS.$("#sm-src").value, n: CRAS.$("#sm-n").value, seed: CRAS.$("#sm-seed").value,
      quantum: CRAS.$("#sm-q").value, aging: CRAS.$("#sm-a").value, starve: CRAS.$("#sm-s").value });
    await CRAS.busy(btn, async () => {
      const d = await api("/api/os/simulate?" + q);
      draw(d);
    });
  }

  const NAMES = { FCFS: "FCFS", SJF: "SJF", SRTF: "SRTF", PRIORITY: "Priority", ROUND_ROBIN: "Round robin", CRAS_AGING: "CRAS aging" };
  const color = (i) => `hsl(${(i * 53 + 20) % 360} 34% 64%)`;

  /** Sentences generated from the actual results, so the commentary can never contradict the numbers. */
  function insights(d) {
    const R = Object.fromEntries(d.results.map((r) => [r.policy, r]));
    const lowest = (k) => d.results.reduce((a, b) => (b[k] < a[k] ? b : a));
    const out = [];
    const ba = lowest("avgWaiting");
    out.push(`Lowest <b>average wait</b>: ${NAMES[ba.policy]} (${ba.avgWaiting} min).`);
    if (d.jobs.some((j) => j.critical)) {
      const bc = lowest("avgCriticalWaiting");
      out.push(`Fastest for <b>critical jobs</b>: ${NAMES[bc.policy]} (${bc.avgCriticalWaiting} min), against ${R.FCFS.avgCriticalWaiting} min under FCFS.`);
    }
    const p = R.PRIORITY, c = R.CRAS_AGING;
    const worst = p.jobs.reduce((a, b) => (b.waiting > a.waiting ? b : a));
    const same = c.jobs.find((j) => j.id === worst.id);
    if (same.waiting < worst.waiting) {
      out.push(`<b>Aging at work:</b> under static priority "${esc(worst.label)}" waits ${worst.waiting} min, the longest wait. With aging it waits ${same.waiting} min. The price: average wait goes from ${p.avgWaiting} to ${c.avgWaiting} min.`);
    } else {
      out.push(`<b>Aging did not help the longest-waiting job</b> ("${esc(worst.label)}", ${worst.waiting} min). In a finite batch the last job always waits for all other work; aging matters when urgent work keeps arriving. Try the <i>steady urgent stream</i> workload.`);
    }
    return out;
  }

  function draw(d) {
    const out = CRAS.$("#sm-out");
    if (!d.results.length) { out.innerHTML = '<div class="card"><div class="empty">The live queue is empty, so there is nothing to simulate. Use the sample workload, or create a few requests first.</div></div>'; return; }
    const R = d.results;
    const best = (k, max) => (max ? Math.max : Math.min)(...R.map((r) => r[k]));
    const cell = (r, k, max, f = (x) => x) => `<span style="${r[k] === best(k, max) ? "color:var(--brass);font-weight:600" : ""}">${f(r[k])}</span>`;
    const T = Math.max(...R.map((r) => r.makespan));
    const idx = {};
    d.jobs.forEach((j, i) => (idx[j.id] = i));
    const crit = new Set(d.jobs.filter((j) => j.critical).map((j) => j.id));

    out.innerHTML = `
      <div class="card"><h3>Comparison</h3><p class="muted">${d.jobs.length} jobs from the ${esc(d.source)} workload. Highlighted values are the best in their column.</p>
        ${CRAS.table([
          { h: "Policy", html: (r) => `<b style="font-weight:500" title="${esc(r.description)}">${NAMES[r.policy]}</b>` },
          { h: "Avg wait", num: 1, html: (r) => cell(r, "avgWaiting") },
          { h: "Critical wait", num: 1, html: (r) => cell(r, "avgCriticalWaiting") },
          { h: "Max wait", num: 1, html: (r) => cell(r, "maxWaiting") },
          { h: "Turnaround", num: 1, html: (r) => cell(r, "avgTurnaround") },
          { h: "Starved", num: 1, html: (r) => cell(r, "starved") },
          { h: "Preemptions", num: 1, v: (r) => r.preemptions },
          { h: "Fairness", num: 1, html: (r) => cell(r, "fairness", true, (x) => x.toFixed(2)) }
        ], R, { rowClass: (r) => (r.policy === "CRAS_AGING" ? "best" : "") })}
        <h4>What this run shows</h4>
        <ul style="margin:.3rem 0 .6rem 1.1rem;padding:0;color:var(--muted)">${insights(d).map((t) => `<li style="margin:.25rem 0">${t}</li>`).join("")}</ul>
        <div class="note"><b>In theory.</b> FCFS suffers the convoy effect; SJF minimises average wait but ignores urgency; static priority serves urgent work first but can starve low-priority jobs; round robin is fair but ignores urgency; aging trades a little average wait for a bound on how long anyone can be starved. <i>Critical wait</i> = mean wait of severity 8-10 jobs.</div></div>
      <div class="card"><h3>Timelines (Gantt)</h3>
        <div class="legend">${d.jobs.map((j, i) => `<span><i style="background:${color(i)}${j.critical ? ";outline:1.5px solid #f0e3c4" : ""}"></i>${esc(j.label)}</span>`).join("")}</div>
        ${R.map((r) => `<div class="g-row"><div class="nm"><b>${NAMES[r.policy]}</b>${r.makespan} min</div>
          <div class="gantt">${r.gantt.map((s) => `<div class="seg ${crit.has(s.jobId) ? "crit" : ""}" title="${esc(d.jobs[idx[s.jobId]].label)}: ${s.start}-${s.end}"
            style="left:${(s.start / T) * 100}%;width:${((s.end - s.start) / T) * 100}%;background:${color(idx[s.jobId])}">${(s.end - s.start) / T > 0.035 ? s.jobId : ""}</div>`).join("")}</div></div>`).join("")}
        <div class="axis">${[0, 0.25, 0.5, 0.75, 1].map((f) => `<span>${Math.round(T * f)}</span>`).join("")}</div>
        <p class="hint" style="margin-left:128px">minutes · outlined blocks are critical jobs (severity 8-10)</p></div>`;
  }

  /* ================================================================ banker's algorithm */
  async function banker(body) {
    body.innerHTML = `
      <div class="card"><h3>Deadlock avoidance with the Banker's algorithm</h3>
        <p class="muted">Relief camps are the processes; Water, Food, Medical Kit and Blanket are the resource types; the available vector is the <b>live depot stock</b> from MySQL. A camp's request is granted only if the resulting state is <b>safe</b>: some order exists in which every camp can still get its full remaining need and finish. Otherwise the camp must wait.</p>
        <div id="bk-state"></div></div>
      <div class="grid g2"><div class="card" id="bk-form-card"></div><div class="card"><h3>Decision</h3><div id="bk-result"><div class="empty">Submit a request to see the decision.</div></div></div></div>
      <div class="card" id="bk-demo-card"></div>`;
    await refreshBanker();
  }

  let bs = null;
  async function refreshBanker() {
    bs = await api("/api/os/banker/state");
    const R = bs.resources;
    const head2 = R.map(() => '<th class="grp">Max</th><th>Held</th><th>Need</th>').join("");
    const rows = bs.camps.map((c) => `<tr><td><b style="font-weight:500">${esc(c.name)}</b></td>${R.map((r, j) => `<td class="grp">${c.max[j]}</td><td>${c.allocated[j]}</td><td>${c.need[j]}</td>`).join("")}
      <td>${CRAS.isManager() ? `<button class="btn sm" data-rel="${c.id}">Finish &amp; release</button>` : ""}</td></tr>`).join("");
    CRAS.$("#bk-state").innerHTML = `
      <div class="banner ${bs.safe ? "ok" : "bad"}"><b>${bs.safe ? "SAFE state" : "UNSAFE state"}</b> ${bs.safe ? "· a safe completion order exists:" : "· no completion order is guaranteed."}
        <div class="seq">${bs.safeSequence.map((n, i) => `${i ? '<span class="arrow">→</span>' : ""}<span class="badge ok">${esc(n)}</span>`).join("")}</div></div>
      <div class="tbl-wrap"><table class="data matrix"><thead>
        <tr><th rowspan="2">Camp</th>${R.map((r) => `<th colspan="3" class="grp">${esc(r.name)}</th>`).join("")}<th rowspan="2"></th></tr><tr>${head2}</tr></thead>
        <tbody>${rows}<tr><td><b style="font-weight:500">Depot available</b></td>${R.map((r) => `<td class="grp" colspan="3" style="text-align:center"><b>${r.available}</b> <span class="faint">${esc(r.unit)}</span></td>`).join("")}<td></td></tr></tbody></table></div>
      <p class="hint">Need = Max − Held. Maxima add up to roughly twice the depot capacity, so unsafe states are reachable.</p>`;
    CRAS.$("#bk-state").onclick = async (e) => {
      const b = e.target.closest("[data-rel]");
      if (!b) return;
      await CRAS.busy(b, async () => { const o = await api(`/api/os/banker/release/${b.dataset.rel}`, { method: "POST" }); CRAS.toast(o.reason, "ok"); await refreshBanker(); });
    };

    const mgr = CRAS.isManager();
    CRAS.$("#bk-form-card").innerHTML = `<h3>Request resources for a camp</h3>` + (mgr ? `
      <form id="bk-form" novalidate>
        <label class="field">Camp<select id="bk-camp">${bs.camps.map((c) => `<option value="${c.id}">${esc(c.name)}</option>`).join("")}</select></label>
        <div class="row" style="margin-top:10px">${R.map((r) => `<label class="field">${esc(r.name)}<input type="number" min="0" value="0" data-res="${r.id}"></label>`).join("")}</div>
        <div style="display:flex;gap:8px;margin-top:14px;flex-wrap:wrap">
          <button class="btn" type="button" id="bk-check">Check safety only</button>
          <button class="btn primary" type="submit">Request</button>
          <button class="btn danger" type="button" id="bk-reset">Reset scenario</button></div></form>` : '<p class="muted">Only managers can change allocations. You can still watch the state update live.</p>');
    if (mgr) {
      const send = async (btn, dry) => {
        const amounts = {};
        CRAS.$$("#bk-form input[data-res]").forEach((i) => { const n = parseInt(i.value, 10) || 0; if (n > 0) amounts[i.dataset.res] = n; });
        await CRAS.busy(btn, async () => {
          const o = await api("/api/os/banker/request", { method: "POST", body: { campId: Number(CRAS.$("#bk-camp").value), amounts, dryRun: dry } });
          show(o);
          if (!dry) await refreshBanker();
        });
      };
      CRAS.$("#bk-form").onsubmit = (e) => { e.preventDefault(); send(e.submitter, false); };
      CRAS.$("#bk-check").onclick = (e) => send(e.currentTarget, true);
      CRAS.$("#bk-reset").onclick = (e) => CRAS.busy(e.currentTarget, async () => { await api("/api/os/banker/reset", { method: "POST" }); CRAS.toast("All camps returned their holdings.", "ok"); CRAS.$("#bk-result").innerHTML = '<div class="empty">Scenario reset.</div>'; await refreshBanker(); });
    }
    CRAS.$("#bk-demo-card").innerHTML = mgr ? `<h3>Stress demo</h3>
      <p class="muted">Resets the scenario, then lets the camps take turns asking for 60 % of their remaining need in every resource. Watch where the algorithm starts refusing.</p>
      <button class="btn" id="bk-demo">Run stress demo</button><div id="bk-demo-out"></div>` : "";
    if (mgr) CRAS.$("#bk-demo").onclick = (e) => CRAS.busy(e.currentTarget, stress);
  }

  function show(o) {
    const seq = (o.safeSequence || []).map((n, i) => `${i ? '<span class="arrow">→</span>' : ""}<span class="badge ok">${esc(n)}</span>`).join("");
    CRAS.$("#bk-result").innerHTML = `<div class="banner ${o.granted ? "ok" : "bad"}"><b>${o.dryRun ? (o.granted ? "Would be granted" : "Would be refused") : (o.granted ? "Granted" : "Refused")}</b><br>${esc(o.reason)}</div>
      ${seq ? `<p class="hint">Safe completion order after this request:</p><div class="seq">${seq}</div>` : ""}`;
  }

  async function stress() {
    await api("/api/os/banker/reset", { method: "POST" });
    await refreshBanker();
    const log = [];
    for (let i = 0; i < 16; i++) {
      const c = bs.camps[i % bs.camps.length];
      const amounts = {};
      bs.resources.forEach((r, j) => { const n = Math.ceil(c.need[j] * 0.6); if (n > 0) amounts[r.id] = n; });
      if (!Object.keys(amounts).length) continue;
      const o = await api("/api/os/banker/request", { method: "POST", body: { campId: c.id, amounts, dryRun: false } });
      log.push({ camp: c.name, o });
      bs = o.state;
    }
    await refreshBanker();
    CRAS.$("#bk-demo-out").innerHTML = CRAS.table([
      { h: "Step", v: (x, i) => i + 1 }, { h: "Camp", v: (x) => x.camp },
      { h: "Outcome", html: (x) => CRAS.badge(x.o.granted ? "granted" : "refused", x.o.granted ? "ok" : "bad") },
      { h: "Reason", v: (x) => x.o.reason }], log, { empty: "Nothing to request: all needs are zero." });
  }

  /* ================================================================ process states */
  async function states(body) {
    const s = await api("/api/os/states");
    const n = (k) => s[k] ?? 0;
    const box = (x, y, t, sub, c) => `<g><rect x="${x}" y="${y}" width="150" height="62" rx="8" fill="#211d18" stroke="${c}" stroke-width="1.4"/>
      <text x="${x + 75}" y="${y + 26}" text-anchor="middle" fill="#e7dfd1" font-family="Cormorant Garamond,serif" font-size="19">${t}</text>
      <text x="${x + 75}" y="${y + 47}" text-anchor="middle" fill="#a2988a" font-size="12">${sub}</text></g>`;
    const arrow = (d, label, lx, ly) => `<path d="${d}" fill="none" stroke="#8d7544" stroke-width="1.4" marker-end="url(#ah)"/><text x="${lx}" y="${ly}" text-anchor="middle" fill="#776f63" font-size="11">${label}</text>`;
    body.innerHTML = `<div class="card"><h3>A request is a process</h3>
      <p class="muted">The life of an emergency request mirrors the classic process-state diagram. Counts are live from the database.</p>
      <svg viewBox="0 0 760 270" style="width:100%;max-width:820px"><defs><marker id="ah" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#8d7544"/></marker></defs>
        ${box(10, 40, "New", "request submitted", "#3a332a")}
        ${box(205, 40, "Ready", `PENDING · ${n("PENDING")}`, "#c9a14a")}
        ${box(400, 40, "Running", `ALLOCATED · ${n("ALLOCATED")}`, "#7d93a8")}
        ${box(595, 40, "Terminated", `COMPLETED · ${n("COMPLETED")}`, "#7f9c70")}
        ${box(205, 180, "Killed", `CANCELLED · ${n("CANCELLED")}`, "#776f63")}
        ${arrow("M160,71 L203,71", "admit", 181, 62)}
        ${arrow("M355,71 L398,71", "dispatch", 377, 62)}
        ${arrow("M550,71 L593,71", "complete", 571, 62)}
        ${arrow("M475,104 C475,150 330,150 290,104", `preempted · ${n("PREEMPTED")}`, 395, 160)}
        ${arrow("M280,104 L280,178", "cancel", 304, 145)}</svg>
      <div class="note"><b>Mapping.</b> The queue is the ready queue; the dispatcher picks by priority with aging; a critical request can <b>preempt</b> a running allocation (it returns to Ready); cancelling kills a waiting request. Banker's algorithm guards the multi-resource allocations of camps.</div></div>`;
  }
})();
