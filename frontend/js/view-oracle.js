(function () {
  const { esc } = CRAS;
  const V = (CRAS.views.oracle = { title: "Risk Oracle", sub: "A machine-learning estimate of shortage risk, kept separate from the rule-based scheduler.", live: false });
  const AI = () => CRAS.cfg.AI_URL;

  V.init = (root) => {
    root.innerHTML = `
      <div id="or-banner"></div>
      <div class="grid g2">
        <div class="card"><h3>Assess shortage risk</h3>
          <form id="or-form" novalidate>
            <label class="field">Resource<select id="or-res"></select></label>
            <label class="field" style="margin-top:10px">Stock ratio (stock ÷ capacity): <b id="or-sr-v" style="color:var(--text)">0.60</b>
              <input id="or-sr" type="range" min="0" max="1" step="0.05" value="0.6"></label>
            <div class="row" style="margin-top:10px">
              <label class="field">Hour of day<input id="or-hr" type="number" min="0" max="23" value="14"></label>
              <label class="field">Event intensity (0-10)<input id="or-ev" type="number" min="0" max="10" step="0.5" value="3"></label>
              <label class="field">Rain, last 6 h (mm)<input id="or-rain" type="number" min="0" step="1" value="0"></label>
              <label class="field">Pending requests<input id="or-pend" type="number" min="0" value="5"></label></div>
            <div style="display:flex;gap:8px;margin-top:14px"><button class="btn primary" type="submit">Assess</button><button class="btn" type="button" id="or-live">Use live stock</button></div>
          </form></div>
        <div class="card"><h3>Assessment</h3><div id="or-out"><div class="empty">Press Assess.</div></div></div>
      </div>
      <div class="card"><h3>How good is the model?</h3><div id="or-model"><div class="empty">Loading…</div></div></div>`;
    CRAS.$("#or-sr").addEventListener("input", (e) => (CRAS.$("#or-sr-v").textContent = Number(e.target.value).toFixed(2)));
    CRAS.$("#or-form").addEventListener("submit", (e) => { e.preventDefault(); assess(e.submitter); });
    CRAS.$("#or-live").addEventListener("click", useLive);
    CRAS.$("#or-res").addEventListener("change", useLive);
  };

  function useLive() {
    const r = CRAS.state.resources.find((x) => String(x.id) === CRAS.$("#or-res").value);
    if (!r) return;
    const ratio = Math.min(1, r.availableQuantity / (r.capacity || r.availableQuantity || 1));
    CRAS.$("#or-sr").value = ratio.toFixed(2);
    CRAS.$("#or-sr-v").textContent = ratio.toFixed(2);
  }

  async function assess(btn) {
    const r = CRAS.state.resources.find((x) => String(x.id) === CRAS.$("#or-res").value);
    const body = { resource: r?.name, hour: +CRAS.$("#or-hr").value, stock_ratio: +CRAS.$("#or-sr").value, stock: r?.availableQuantity ?? 0,
      event_intensity: +CRAS.$("#or-ev").value, rain_6h: +CRAS.$("#or-rain").value, pending_count: +CRAS.$("#or-pend").value };
    await CRAS.busy(btn, async () => {
      let res;
      try {
        const http = await fetch(AI() + "/predict", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
        res = await http.json();
        if (!http.ok) throw new Error(res.error || "AI service error");
      } catch (e) { throw new Error(e instanceof TypeError ? `The AI service at ${AI()} is not reachable. Start it with: python ai/serve.py` : e.message); }
      const pct = Math.round(res.shortageRisk * 100), conf = Math.round(res.confidence * 100);
      const cls = res.riskLevel === "HIGH" ? "bad" : res.riskLevel === "MEDIUM" ? "warn" : "ok";
      CRAS.$("#or-out").innerHTML = `
        <div style="display:flex;align-items:baseline;gap:12px"><span style="font-family:var(--serif);font-size:3rem;line-height:1">${pct}%</span>${CRAS.badge(res.riskLevel + " risk", cls)}</div>
        ${CRAS.meter(pct, cls)}
        <p class="muted" style="margin-top:10px">Model confidence <b style="color:var(--text)">${conf}%</b> · alert threshold ${Math.round(res.threshold * 100)}%</p>
        <div class="note">${conf < 50 ? "<b>Low confidence.</b> The scheduler ignores low-confidence signals and falls back to its rule-based formula. " : ""}The Oracle predicts the <i>probability that this resource runs short soon</i>. It informs priority, but never replaces the rules.</div>`;
    });
  }

  V.load = async () => {
    await CRAS.loadRefs();
    const keep = CRAS.$("#or-res").value;
    CRAS.$("#or-res").innerHTML = CRAS.state.resources.map((r) => `<option value="${r.id}">${esc(r.name)}</option>`).join("");
    if (keep) CRAS.$("#or-res").value = keep; else useLive();
    try {
      const h = await fetch(AI() + "/health");
      if (!h.ok) throw new Error();
      CRAS.$("#or-banner").innerHTML = "";
      const m = await (await fetch(AI() + "/model-info")).json();
      const c = m.classification || [];
      const pick = (k) => c.find((x) => x.model.startsWith(k));
      const rows = c.filter((x) => !x.model.endsWith("(raw)") || x.model.startsWith("Logistic"));
      CRAS.$("#or-model").innerHTML = `
        <p class="muted">Shortages are rare (${(m.split.shortage_rate_test * 100).toFixed(1)} % of test cases), so plain accuracy would be misleading. Judge it by PR-AUC, precision and recall.</p>
        ${CRAS.table([{ h: "Model", v: (x) => x.model }, { h: "ROC-AUC", num: 1, v: (x) => x.roc_auc }, { h: "PR-AUC", num: 1, v: (x) => x.pr_auc }, { h: "Precision", num: 1, v: (x) => x.precision }, { h: "Recall", num: 1, v: (x) => x.recall }, { h: "F1", num: 1, v: (x) => x.f1 }], rows)}
        <div class="grid g2" style="margin-top:14px"><div><h4>Most influential features</h4>${(m.top_features || []).slice(0, 6).map((f) => `<div style="margin:7px 0"><div style="display:flex;justify-content:space-between;font-size:.84rem"><span>${esc(f.feature)}</span><span class="faint">${f.importance.toFixed(3)}</span></div>${CRAS.meter(f.importance / m.top_features[0].importance * 100, "")}</div>`).join("")}</div>
        <div><h4>Demand forecast</h4><p class="muted">Random-forest forecast error (WAPE) <b style="color:var(--text)">${(m.demand_forecast.wape_random_forest * 100).toFixed(0)} %</b> versus <b style="color:var(--text)">${(m.demand_forecast.wape_moving_window_baseline * 100).toFixed(0)} %</b> for a simple moving-window baseline. Lower is better.</p>
        <div class="note">Trained on ${m.split.train.toLocaleString()} synthetic samples. These are offline results on generated data, not production performance.</div></div></div>`;
    } catch {
      CRAS.$("#or-banner").innerHTML = `<div class="banner warn"><b>AI service offline.</b> Start it with <code>python ai/serve.py</code>. CRAS keeps working: the scheduler automatically falls back to its rule-based priority formula.</div>`;
      CRAS.$("#or-model").innerHTML = '<div class="empty">Model details unavailable while the AI service is offline.</div>';
    }
  };
})();
