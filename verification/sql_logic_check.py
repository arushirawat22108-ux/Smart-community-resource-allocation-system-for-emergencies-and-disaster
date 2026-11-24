"""
Checks the LOGIC behind db/01_phase3_schema.sql and db/02_demos...sql on a real SQL engine (SQLite).
Same ideas, SQLite dialect: CHECK constraint, triggers, ledger, request history, atomic
allocation with rollback, lost update vs exclusive lock, idempotency key, window functions.
NOT verified here: the MySQL-specific syntax (DELIMITER, stored procedure, FOR UPDATE, SIGNAL).
Run: python verification/sql_logic_check.py
"""
import sqlite3
import sys
import threading
import time
import tempfile
import os

passed = failed = 0
def check(name, ok, detail=""):
    global passed, failed
    if ok: passed += 1; print("  PASS ", name)
    else:  failed += 1; print("  FAIL ", name, "->", detail)

SCHEMA = """
CREATE TABLE resources (id INTEGER PRIMARY KEY, name TEXT UNIQUE, available_quantity INTEGER NOT NULL,
                        CONSTRAINT chk_stock_nonneg CHECK (available_quantity >= 0));
CREATE TABLE emergency_requests (id INTEGER PRIMARY KEY, resource_id INT, quantity INT, status TEXT,
                        priority_score INT, minutes_waiting INT);
CREATE TABLE allocations (id INTEGER PRIMARY KEY, request_id INT, resource_id INT, quantity INT, status TEXT);
CREATE TABLE stock_ledger (id INTEGER PRIMARY KEY, resource_id INT, delta INT, balance_after INT, reason TEXT);
CREATE TABLE request_events (id INTEGER PRIMARY KEY, request_id INT, old_status TEXT, new_status TEXT);
CREATE TABLE idempotency_keys (idem_key TEXT PRIMARY KEY, endpoint TEXT);
CREATE TRIGGER trg_resources_ledger AFTER UPDATE ON resources
WHEN NEW.available_quantity <> OLD.available_quantity
BEGIN INSERT INTO stock_ledger (resource_id, delta, balance_after, reason)
      VALUES (NEW.id, NEW.available_quantity - OLD.available_quantity, NEW.available_quantity, 'UPDATE'); END;
CREATE TRIGGER trg_requests_history AFTER UPDATE ON emergency_requests
WHEN NEW.status <> OLD.status
BEGIN INSERT INTO request_events (request_id, old_status, new_status) VALUES (NEW.id, OLD.status, NEW.status); END;
"""

def fresh(path=":memory:"):
    c = sqlite3.connect(path, isolation_level=None, timeout=60)
    c.executescript(SCHEMA)
    return c

print("== integrity and triggers ==")
c = fresh()
c.execute("INSERT INTO resources VALUES (1,'Water',100)")
try:
    c.execute("UPDATE resources SET available_quantity = -5 WHERE id=1"); ok = False
except sqlite3.IntegrityError: ok = True
check("CHECK constraint refuses negative stock", ok)
check("refused update left stock unchanged", c.execute("SELECT available_quantity FROM resources").fetchone()[0] == 100)
c.execute("UPDATE resources SET available_quantity = 70 WHERE id=1")
c.execute("UPDATE resources SET available_quantity = 90 WHERE id=1")
rows = c.execute("SELECT delta, balance_after FROM stock_ledger ORDER BY id").fetchall()
check("ledger trigger records each change (delta, balance)", rows == [(-30, 70), (20, 90)], rows)
check("ledger sum equals net stock change", sum(r[0] for r in rows) == 90 - 100)
c.execute("UPDATE resources SET name = 'Water' WHERE id=1")
check("ledger ignores updates that do not change stock", c.execute("SELECT COUNT(*) FROM stock_ledger").fetchone()[0] == 2)
c.execute("INSERT INTO emergency_requests VALUES (1,1,10,'PENDING',50,0)")
c.execute("UPDATE emergency_requests SET status='ALLOCATED' WHERE id=1")
check("status-history trigger records PENDING -> ALLOCATED",
      c.execute("SELECT old_status,new_status FROM request_events").fetchall() == [("PENDING", "ALLOCATED")])

print("== atomic allocation (all or nothing) ==")
def allocate(conn, req_id):
    conn.execute("BEGIN IMMEDIATE")
    try:
        rid, qty, status = conn.execute("SELECT resource_id, quantity, status FROM emergency_requests WHERE id=?", (req_id,)).fetchone()
        if status not in ("PENDING", "PREEMPTED"): raise ValueError("not waiting")
        stock = conn.execute("SELECT available_quantity FROM resources WHERE id=?", (rid,)).fetchone()[0]
        if stock < qty: raise ValueError("Insufficient inventory")
        conn.execute("UPDATE resources SET available_quantity = available_quantity - ? WHERE id=?", (qty, rid))
        conn.execute("UPDATE emergency_requests SET status='ALLOCATED' WHERE id=?", (req_id,))
        conn.execute("INSERT INTO allocations (request_id, resource_id, quantity, status) VALUES (?,?,?, 'ACTIVE')", (req_id, rid, qty))
        if req_id == 99: raise RuntimeError("simulated crash after all writes")
        conn.execute("COMMIT"); return True
    except Exception:
        conn.execute("ROLLBACK"); return False

c = fresh()
c.execute("INSERT INTO resources VALUES (1,'Water',10)")
c.execute("INSERT INTO emergency_requests VALUES (1,1,50,'PENDING',50,0)")
c.execute("INSERT INTO emergency_requests VALUES (99,1,5,'PENDING',50,0)")
check("insufficient stock: nothing changes", not allocate(c, 1) and
      c.execute("SELECT available_quantity FROM resources").fetchone()[0] == 10 and
      c.execute("SELECT COUNT(*) FROM allocations").fetchone()[0] == 0)
check("crash after all writes: whole transaction rolled back (atomicity)", not allocate(c, 99) and
      c.execute("SELECT available_quantity FROM resources").fetchone()[0] == 10 and
      c.execute("SELECT status FROM emergency_requests WHERE id=99").fetchone()[0] == "PENDING" and
      c.execute("SELECT COUNT(*) FROM allocations").fetchone()[0] == 0)

print("== concurrency: lost update vs exclusive lock (20 stock, 60 parallel requests) ==")
def race(use_lock, stock=20, n=60):
    path = os.path.join(tempfile.mkdtemp(), "t.db")
    base = fresh(path)
    base.execute("INSERT INTO resources VALUES (1,'Water',?)", (stock,))
    for i in range(1, n + 1):
        base.execute("INSERT INTO emergency_requests VALUES (?,1,1,'PENDING',50,0)", (i,))
    barrier = threading.Barrier(n); results = []
    def worker(i):
        conn = sqlite3.connect(path, isolation_level=None, timeout=120)
        barrier.wait()
        if use_lock:
            results.append(allocate(conn, i))
        else:                                   # read, think, write: no lock => lost updates
            s = conn.execute("SELECT available_quantity FROM resources WHERE id=1").fetchone()[0]
            time.sleep(0.01)
            if s >= 1:
                conn.execute("UPDATE resources SET available_quantity=? WHERE id=1", (s - 1,)); results.append(True)
            else: results.append(False)
        conn.close()
    ts = [threading.Thread(target=worker, args=(i,)) for i in range(1, n + 1)]
    [t.start() for t in ts]; [t.join() for t in ts]
    final = base.execute("SELECT available_quantity FROM resources").fetchone()[0]
    return sum(results), final
ok_n, final = race(True)
check("with exclusive lock: exactly 20 succeed, stock 0", ok_n == 20 and final == 0, f"{ok_n}/{final}")
bad_n, bad_final = race(False)
check("without lock the same test shows lost updates (control)", bad_n != 20 or bad_final != 0, f"{bad_n}/{bad_final}")
print(f"    control detail: {bad_n} succeeded, final stock {bad_final}")

print("== idempotency key ==")
c = fresh()
c.execute("INSERT INTO idempotency_keys VALUES ('abc-123','POST /api/requests')")
try:
    c.execute("INSERT INTO idempotency_keys VALUES ('abc-123','POST /api/requests')"); dup = False
except sqlite3.IntegrityError: dup = True
check("duplicate idempotency key rejected by primary key", dup)

print("== analytics queries (window function, CTE) ==")
c = fresh()
c.execute("INSERT INTO resources VALUES (1,'Water',100),(2,'Food',100)")
c.executemany("INSERT INTO emergency_requests VALUES (?,?,?,?,?,?)",
              [(1,1,5,'PENDING',40,10),(2,1,5,'PENDING',90,5),(3,1,5,'PENDING',70,300),(4,2,5,'PENDING',20,1),(5,2,5,'ALLOCATED',99,1)])
q = c.execute("""SELECT id, ROW_NUMBER() OVER (PARTITION BY resource_id ORDER BY priority_score DESC) AS pos
                 FROM emergency_requests WHERE status='PENDING' ORDER BY resource_id, pos""").fetchall()
check("ROW_NUMBER() ranks each resource queue by priority", q == [(2,1),(3,2),(1,3),(4,1)], q)
starved = c.execute("""WITH waiting AS (SELECT id, minutes_waiting FROM emergency_requests WHERE status='PENDING')
                       SELECT id FROM waiting WHERE minutes_waiting > 240""").fetchall()
check("CTE starvation watch finds requests waiting > 4 h", starved == [(3,)], starved)

print(f"\nRESULT: {passed} passed, {failed} failed")
sys.exit(0 if failed == 0 else 1)
