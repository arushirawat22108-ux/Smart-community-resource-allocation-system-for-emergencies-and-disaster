(function () {
  const { esc, api } = CRAS;
  const V = (CRAS.views.dbms = {
    title: "DBMS Lab",
    sub: "Concurrency control, integrity and query performance, demonstrated live on your MySQL server.",
    live: false
  });
  let tabs, first = true;

  V.init = (root) => {
    tabs = CRAS.tabs(root, [
      { id: "conc", label: "Concurrency" }, { id: "integrity", label: "Integrity & ledger" },
      { id: "plans", label: "Query plans" }, { id: "analytics", label: "Analytics (SQL)" }, { id: "objects", label: "Schema objects" }
    ], (id, body) => ({ conc, integrity, plans, analytics, objects })[id](body));
  };
  V.load = async () => {
    if (first) { first = false; return; }
    tabs.open(CRAS.$(".tab.active", CRAS.$("#v-dbms"))?.dataset.t || "conc");
  };

  const genericTable = (rows, empty) => {
    if (!rows || !rows.length) return `<div class="empty">${esc(empty || "No rows.")}</div>`;
    const keys = Object.keys(rows[0]);
    return CRAS.table(keys.map((k) => ({ h: k.replace(/_/g, " "), num: typeof rows[0][k] === "number", v: (r) => (r[k] === null || r[k] === undefined ? "NULL" : String(r[k])) })), rows);
  };

  /* ====================================================================== concurrency */
  function conc(body) {
    body.innerHTML = `
      <div class="card"><h3>1 · Race condition: four ways to sell the last units</h3>
        <p class="muted">Many threads try to take stock from the same row at the same moment. Runs on a scratch table, never on real stock.</p>
        <div class="row">
          <label class="field">Strategy<select id="rc-mode"><option value="UNSAFE">UNSAFE: read, think, write</option><option value="PESSIMISTIC">PESSIMISTIC: SELECT … FOR UPDATE</option><option value="OPTIMISTIC">OPTIMISTIC: version check + retry</option><option value="ATOMIC">ATOMIC: UPDATE … WHERE stock ≥ n</option></select></label>
          <label class="field">Threads<input id="rc-n" type="number" min="2" max="32" value="15"></label>
          <label class="field">Units each<input id="rc-u" type="number" min="1" max="50" value="10"></label>
          <label class="field">Initial stock<input id="rc-s" type="number" min="10" max="1000" value="100"></label>
          <button class="btn primary fit" id="rc-run">Run</button><button class="btn fit" id="rc-all">Compare all four</button></div>
        <div id="rc-out"></div></div>

      <div class="card"><h3>2 · Isolation levels: what can a reader see?</h3>
        <p class="muted">T1 reads, T2 changes and inserts a row without committing, T1 reads again, T2 commits, T1 reads a third time.</p>
        <div class="row"><label class="field">Isolation level<select id="is-lvl"><option>READ_UNCOMMITTED</option><option>READ_COMMITTED</option><option selected>REPEATABLE_READ</option><option>SERIALIZABLE</option></select></label>
          <button class="btn primary fit" id="is-run">Run</button><button class="btn fit" id="is-all">Compare all levels</button></div>
        <div id="is-out"></div></div>

      <div class="card"><h3>3 · Deadlock</h3>
        <p class="muted">Two transactions lock the same two rows. In opposite order they deadlock and InnoDB kills one; in a fixed global order a deadlock is impossible. CRAS locks several resources in ascending id order for exactly this reason.</p>
        <div class="row"><button class="btn danger fit" id="dl-bad">Opposite order</button><button class="btn primary fit" id="dl-good">Same order (ascending)</button></div>
        <div id="dl-out"></div></div>`;

    const raceCols = [
      { h: "Strategy", v: (r) => r.mode }, { h: "Succeeded", num: 1, v: (r) => r.succeeded }, { h: "Rejected", num: 1, v: (r) => r.rejected },
      { h: "Retries", num: 1, v: (r) => r.retries }, { h: "Final stock", num: 1, v: (r) => r.finalStock }, { h: "Expected", num: 1, v: (r) => r.expectedStock },
      { h: "Oversold", num: 1, v: (r) => r.oversoldUnits }, { h: "Time", num: 1, v: (r) => r.elapsedMs + " ms" },
      { h: "Result", html: (r) => CRAS.badge(r.consistent ? "correct" : "race condition", r.consistent ? "ok" : "bad") }];
    const params = () => `threads=${CRAS.$("#rc-n").value}&units=${CRAS.$("#rc-u").value}&stock=${CRAS.$("#rc-s").value}`;
    CRAS.$("#rc-run").onclick = (e) => CRAS.busy(e.currentTarget, async () => {
      const r = await api(`/api/lab/race?mode=${CRAS.$("#rc-mode").value}&${params()}`, { method: "POST" });
      CRAS.$("#rc-out").innerHTML = `<div class="banner ${r.consistent ? "ok" : "bad"}">${esc(r.verdict)}</div>${CRAS.table(raceCols, [r])}`;
    });
    CRAS.$("#rc-all").onclick = (e) => CRAS.busy(e.currentTarget, async () => {
      const rows = [];
      for (const m of ["UNSAFE", "PESSIMISTIC", "OPTIMISTIC", "ATOMIC"]) rows.push(await api(`/api/lab/race?mode=${m}&${params()}`, { method: "POST" }));
      CRAS.$("#rc-out").innerHTML = CRAS.table(raceCols, rows) + `<div class="note"><b>Reading it.</b> UNSAFE lets every thread read the same stale value, so it oversells and loses updates. PESSIMISTIC serialises access with a row lock (correct, slower). OPTIMISTIC lets everyone try and retries the losers (see Retries). ATOMIC folds check and update into one statement: correct and usually fastest. CRAS uses pessimistic locking for allocation because it also needs to read related rows consistently.</div>`;
    });

    const flags = (r) => ["dirtyRead", "nonRepeatableRead", "phantomRead"].map((k) => CRAS.badge({ dirtyRead: "dirty read", nonRepeatableRead: "non-repeatable read", phantomRead: "phantom read" }[k], r[k] ? "bad" : "ok")).join(" ")
      + (r.writerBlocked ? " " + CRAS.badge("writer blocked", "warn") : "");
    CRAS.$("#is-run").onclick = (e) => CRAS.busy(e.currentTarget, async () => {
      const r = await api(`/api/lab/isolation?level=${CRAS.$("#is-lvl").value}`, { method: "POST" });
      CRAS.$("#is-out").innerHTML = `<div style="margin:.7rem 0">${flags(r)}</div><ol class="steps">${r.steps.map((s) => `<li><b style="font-weight:500">${esc(s.what)}</b>${s.result ? " · " + esc(s.result) : ""}<span class="n">${esc(s.note)}</span></li>`).join("")}</ol>`;
    });
    CRAS.$("#is-all").onclick = (e) => CRAS.busy(e.currentTarget, async () => {
      const rows = [];
      for (const l of ["READ_UNCOMMITTED", "READ_COMMITTED", "REPEATABLE_READ", "SERIALIZABLE"]) rows.push(await api(`/api/lab/isolation?level=${l}`, { method: "POST" }));
      const mark = (b) => CRAS.badge(b ? "possible" : "prevented", b ? "bad" : "ok");
      CRAS.$("#is-out").innerHTML = CRAS.table([{ h: "Level", v: (r) => r.level }, { h: "Dirty read", html: (r) => mark(r.dirtyRead) },
        { h: "Non-repeatable read", html: (r) => mark(r.nonRepeatableRead) }, { h: "Phantom read", html: (r) => mark(r.phantomRead) },
        { h: "Writer blocked", html: (r) => (r.writerBlocked ? CRAS.badge("yes", "warn") : "no") }], rows)
        + `<div class="note">MySQL's default, <b>REPEATABLE READ</b>, already prevents phantoms for plain reads through snapshots. <b>SERIALIZABLE</b> blocks the writer instead, trading concurrency for safety.</div>`;
    });

    const dl = (ordered) => (e) => CRAS.busy(e.currentTarget, async () => {
      const r = await api(`/api/lab/deadlock?ordered=${ordered}`, { method: "POST" });
      CRAS.$("#dl-out").innerHTML = `<div class="banner ${r.deadlockDetected ? "bad" : "ok"}"><b>${r.deadlockDetected ? "Deadlock detected" : "No deadlock"}</b> · ${esc(r.lockOrder)}</div>
        ${CRAS.table([{ h: "Txn", v: (t) => t.name }, { h: "Lock order", v: (t) => t.lockOrder }, { h: "Outcome", html: (t) => CRAS.badge(t.outcome, t.deadlockVictim ? "bad" : t.outcome === "COMMITTED" ? "ok" : "warn") }, { h: "MySQL said", html: (t) => `<span class="faint">${esc(t.mysqlError || "")}</span>` }], r.transactions)}
        <div class="note">${esc(r.explanation)}</div>`;
    });
    CRAS.$("#dl-bad").onclick = dl(false);
    CRAS.$("#dl-good").onclick = dl(true);
  }

  /* ====================================================================== integrity */
  async function integrity(body) {
    body.innerHTML = `
      <div class="card"><h3>Ledger reconciliation</h3>
        <p class="muted">Every change to stock is recorded by a <b>database trigger</b> in <code>stock_ledger</code>, so no code path (not even manual SQL) can change stock unnoticed. The invariant: <b>stock = sum of all ledger deltas</b>.</p>
        <div id="in-rec"></div><button class="btn" id="in-refresh" style="margin-top:10px">Re-check</button></div>
      <div class="card"><h3>Recent stock movements</h3><div class="row" style="margin-bottom:10px"><label class="field fit" style="min-width:220px">Resource<select id="in-res"></select></label></div><div id="in-led"></div></div>
      <div class="card"><h3>Try it in MySQL Workbench</h3>
        <pre class="sql">USE cras_db;
-- 1. A manual change is still recorded, attributed to your MySQL account:
UPDATE resources SET available_quantity = available_quantity - 5 WHERE id = 1;
-- 2. Negative stock is refused by the trigger and by the CHECK constraint:
UPDATE resources SET available_quantity = -1 WHERE id = 1;
-- 3. The same allocation logic as a stored procedure (replace 1 with a pending request id):
CALL sp_allocate(1, 'workbench');</pre>
        <p class="hint">After each statement press <b>Re-check</b>: the movement appears in the ledger and the books still balance.</p></div>`;
    await CRAS.loadRefs();
    CRAS.$("#in-res").innerHTML = '<option value="">All resources</option>' + CRAS.state.resources.map((r) => `<option value="${r.id}">${esc(r.name)}</option>`).join("");
    const load = async () => {
      const [rec, led] = await Promise.all([api("/api/dbms/reconcile"), api(`/api/dbms/ledger?limit=40${CRAS.$("#in-res").value ? "&resourceId=" + CRAS.$("#in-res").value : ""}`)]);
      CRAS.$("#in-rec").innerHTML = `<div class="banner ${rec.balanced ? "ok" : "bad"}">${rec.balanced ? "Balanced: stock equals the ledger for every resource." : "DRIFT: stock was changed without a ledger entry."}</div>` +
        CRAS.table([{ h: "Resource", v: (r) => r.name }, { h: "Stock", num: 1, v: (r) => r.stock }, { h: "Ledger sum", num: 1, v: (r) => r.ledgerSum }, { h: "Drift", num: 1, html: (r) => (Number(r.drift) === 0 ? CRAS.badge("0", "ok") : CRAS.badge(r.drift, "bad")) }], rec.rows);
      CRAS.$("#in-led").innerHTML = CRAS.table([{ h: "#", v: (r) => r.id }, { h: "When", v: (r) => r.at }, { h: "Resource", v: (r) => r.resource },
        { h: "Change", num: 1, html: (r) => `<b style="color:${r.delta < 0 ? "var(--bad)" : "var(--ok)"}">${r.delta > 0 ? "+" : ""}${r.delta}</b>` },
        { h: "Balance", num: 1, v: (r) => r.balanceAfter }, { h: "Reason", html: (r) => CRAS.badge(r.reason, r.reason === "DB_DIRECT" ? "warn" : "") }, { h: "Actor", html: (r) => `<span class="faint">${esc(r.actor || "")}</span>` }], led, { empty: "No movements yet." });
    };
    CRAS.$("#in-refresh").onclick = (e) => CRAS.busy(e.currentTarget, load);
    CRAS.$("#in-res").onchange = () => load().catch((e) => CRAS.toast(e.message, "bad"));
    await load();
  }

  /* ====================================================================== plans */
  async function plans(body) {
    body.innerHTML = `
      <div class="card"><h3>EXPLAIN: how MySQL runs a query</h3>
        <p class="muted">Fixed, whitelisted queries only. Read the <code>type</code> (ALL = full scan, ref/range = index), <code>key</code> (index used) and <code>Extra</code> (look for “Using filesort”).</p>
        <div class="row"><label class="field"><select id="pl-sel"><option value="queue">Scheduler queue query (indexed)</option><option value="ledger">Ledger lookup (indexed)</option><option value="scan">Function on column (defeats index)</option><option value="sargable">Same question as a range (index-friendly)</option></select></label>
          <button class="btn primary fit" id="pl-run">Explain</button></div><div id="pl-out"></div></div>
      <div class="card"><h3>Indexes</h3><div id="pl-idx"></div></div>`;
    const run = async () => {
      const r = await api(`/api/dbms/explain?plan=${CRAS.$("#pl-sel").value}`);
      CRAS.$("#pl-out").innerHTML = `<pre class="sql">${esc(r.sql)}</pre>${genericTable(r.plan)}<div class="note">${esc(r.tip)}</div>`;
    };
    CRAS.$("#pl-run").onclick = (e) => CRAS.busy(e.currentTarget, run);
    CRAS.$("#pl-sel").onchange = () => run().catch((e) => CRAS.toast(e.message, "bad"));
    await run();
    CRAS.$("#pl-idx").innerHTML = CRAS.table([{ h: "Table", v: (r) => r.tbl }, { h: "Index", html: (r) => `<b style="font-weight:500">${esc(r.name)}</b>` }, { h: "Pos", num: 1, v: (r) => r.pos }, { h: "Column", v: (r) => r.col }, { h: "Unique", v: (r) => (Number(r.nonUnique) === 0 ? "yes" : "no") }, { h: "Cardinality", num: 1, v: (r) => r.cardinality ?? "" }], await api("/api/dbms/indexes"));
  }

  /* ====================================================================== analytics */
  async function analytics(body) {
    body.innerHTML = '<div class="empty"><span class="spin"></span>Running analytical queries…</div>';
    const secs = await api("/api/dbms/analytics");
    body.innerHTML = secs.map((s, i) => `<div class="card"><h3>${esc(s.title)}</h3><p class="muted">${esc(s.concept)}</p>
      ${s.error ? `<div class="banner warn">This query could not run: ${esc(s.error)}</div>` : i === 0 ? chart(s.rows) : genericTable(s.rows, "No data yet.")}
      <details><summary>Show the SQL</summary><pre class="sql">${esc(s.sql)}</pre></details></div>`).join("");
  }

  function chart(rows) {
    if (!rows.length) return '<div class="empty">No data.</div>';
    const max = Math.max(1, ...rows.map((r) => Number(r.requests)));
    const W = 760, H = 150, bw = W / rows.length;
    const bars = rows.map((r, i) => {
      const h = (Number(r.requests) / max) * (H - 34), c = (Number(r.critical) / max) * (H - 34);
      return `<rect x="${i * bw + 3}" y="${H - 20 - h}" width="${bw - 6}" height="${h}" fill="#8d7544"><title>${esc(r.hour)}: ${r.requests} requests, ${r.critical} critical</title></rect>
        <rect x="${i * bw + 3}" y="${H - 20 - c}" width="${bw - 6}" height="${c}" fill="#b4574a"/>
        ${i % 3 === 0 ? `<text x="${i * bw + bw / 2}" y="${H - 5}" text-anchor="middle" fill="#776f63" font-size="10">${esc(r.hour)}</text>` : ""}`;
    }).join("");
    return `<svg viewBox="0 0 ${W} ${H}" style="width:100%;max-width:${W}px"><line x1="0" x2="${W}" y1="${H - 20}" y2="${H - 20}" stroke="#3a332a"/>${bars}</svg><p class="hint">Bars: requests per hour · red: critical (severity 8-10). Hours with no requests are kept by the recursive CTE.</p>`;
  }

  /* ====================================================================== objects */
  async function objects(body) {
    const s = await api("/api/dbms/status");
    const bad = s.bootstrap.filter((b) => b.status !== "OK");
    body.innerHTML = `
      <div class="grid g3"><div class="kpi"><div class="v" style="font-size:1.4rem">${esc(s.database)}</div><div class="l">database</div></div>
        <div class="kpi"><div class="v" style="font-size:1.4rem">${esc(s.version)}</div><div class="l">MySQL version</div></div>
        <div class="kpi"><div class="v" style="font-size:1.4rem">${esc(s.isolation)}</div><div class="l">default isolation</div></div></div>
      <div class="card" style="margin-top:16px"><h3>Installed on start-up</h3>
        <div class="banner ${bad.length ? "bad" : "ok"}">${bad.length ? `${bad.length} step(s) failed. The application still runs, but those features are missing. See the error column.` : "Every database object was installed successfully."}</div>
        ${CRAS.table([{ h: "Object", html: (b) => `<span class="mono">${esc(b.name)}</span>` }, { h: "Kind", v: (b) => b.kind }, { h: "Status", html: (b) => CRAS.badge(b.status, b.status === "OK" ? "ok" : "bad") }, { h: "Error", html: (b) => `<span class="faint">${esc(b.error)}</span>` }], s.bootstrap)}</div>
      <div class="grid g2"><div class="card"><h3>Triggers</h3>${CRAS.table([{ h: "Name", v: (t) => t.name }, { h: "When", v: (t) => `${t.timing} ${t.event}` }, { h: "Table", v: (t) => t.tbl }], s.triggers)}</div>
        <div class="card"><h3>Procedures, views, constraints</h3>${CRAS.table([{ h: "Name", v: (t) => t.name }, { h: "Type", v: (t) => t.type }, { h: "Table", v: (t) => t.tbl || "" }],
          [...s.routines, ...s.views.map((v) => ({ name: v.name, type: "VIEW" })), ...s.constraints])}</div></div>`;
  }
})();
