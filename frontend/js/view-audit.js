(function () {
  const { esc, api } = CRAS;
  const V = (CRAS.views.audit = { title: "Audit Ledger", sub: "Every sensitive action is recorded in a tamper-evident, hash-chained log." });

  V.init = (root) => {
    root.innerHTML = `
      <div class="card"><h3>Integrity check</h3>
        <p class="muted">Each entry stores the SHA-256 hash of its own content <b>and the previous entry's hash</b>. Edit or delete any row, even directly in MySQL, and the chain no longer verifies from that point on.</p>
        <button class="btn primary" id="au-verify">Verify the whole chain</button><div id="au-res"></div>
        <details><summary>Try to tamper with it</summary>
          <pre class="sql">-- In MySQL Workbench (pick any id from the table below):
UPDATE audit_logs SET detail = 'edited' WHERE id = 5;
-- Then press "Verify the whole chain": it pinpoints entry #5.</pre></details></div>
      <div class="card"><h3>Recent activity</h3><div id="au-tbl"></div></div>`;
    CRAS.$("#au-verify").onclick = (e) => CRAS.busy(e.currentTarget, async () => {
      const r = await api("/api/audit/verify");
      CRAS.$("#au-res").innerHTML = `<div class="banner ${r.valid ? "ok" : "bad"}"><b>${r.valid ? "Chain intact" : "Tampering detected"}</b> · ${esc(r.message)}</div>
        <p class="hint">${r.sealed} sealed of ${r.total} entries${r.legacy ? ` · ${r.legacy} older entries predate chaining` : ""}${r.headHash ? ` · head <span class="mono">${esc(r.headHash.slice(0, 16))}…</span>` : ""}</p>`;
    });
  };

  V.load = async () => {
    const rows = await api("/api/audit");
    CRAS.$("#au-tbl").innerHTML = CRAS.table([
      { h: "#", v: (r) => r.id }, { h: "When", v: (r) => CRAS.dt(r.createdAt) }, { h: "Actor", html: (r) => `<span class="faint">${esc(r.actor)}</span>` },
      { h: "Action", html: (r) => CRAS.badge(r.action, /DENY|PREEMPT/.test(r.action) ? "bad" : /LOGIN/.test(r.action) ? "info" : "") },
      { h: "Detail", v: (r) => r.detail || "" },
      { h: "Hash", html: (r) => (r.entryHash ? `<span class="mono faint">${esc(r.entryHash.slice(0, 10))}…</span>` : CRAS.badge("legacy", "")) }
    ], rows, { empty: "No audit entries yet." });
  };
})();
