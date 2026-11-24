package com.cras.service;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Live concurrency experiments against the real MySQL server, on a scratch table (lab_inventory)
 * so real stock is never touched. Plain JDBC on purpose: every BEGIN, lock and COMMIT is explicit,
 * which makes the behaviour easy to explain.
 *
 *  race       N threads try to take stock at once under four strategies:
 *               UNSAFE       read, think, write           -> lost updates, overselling
 *               PESSIMISTIC  SELECT ... FOR UPDATE        -> correct, serialised
 *               OPTIMISTIC   version column + retry (CAS) -> correct, retries under contention
 *               ATOMIC       UPDATE ... WHERE stock >= n  -> correct, one statement
 *  isolation  dirty read / non-repeatable read / phantom read under each isolation level
 *  deadlock   two transactions lock two rows in opposite order (InnoDB kills one),
 *             versus the same work with a fixed lock order (no deadlock)
 */
@Service
public class ConcurrencyLabService {

    private static final int THINK_MS = 25;                    // simulated work between read and write
    private final DataSource ds;
    private final ReentrantLock busy = new ReentrantLock();

    public ConcurrencyLabService(DataSource ds) {
        this.ds = ds;
    }

    private interface Lab<T> { T run() throws Exception; }

    private <T> T exclusive(Lab<T> body) {
        if (!busy.tryLock()) throw new IllegalStateException("Another lab experiment is still running. Try again in a moment.");
        try {
            ensureRows();
            return body.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Lab failed: " + e.getMessage(), e);
        } finally {
            busy.unlock();
        }
    }

    private void ensureRows() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT IGNORE INTO lab_inventory (id, name, stock) VALUES (1, 'Lab Water', 100), (2, 'Lab Food', 100)");
        }
    }

    // ======================================================================== race

    private record Worker(boolean success, int retries) {}

    public Map<String, Object> race(String mode, int threads, int units, int initial) {
        String m = mode.toUpperCase(Locale.ROOT);
        if (!List.of("UNSAFE", "PESSIMISTIC", "OPTIMISTIC", "ATOMIC").contains(m))
            throw new IllegalArgumentException("mode must be UNSAFE, PESSIMISTIC, OPTIMISTIC or ATOMIC");
        int n = Math.max(2, Math.min(32, threads));
        int u = Math.max(1, Math.min(50, units));
        int start = Math.max(10, Math.min(1000, initial));
        return exclusive(() -> {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE lab_inventory SET stock = " + start + ", version = 0 WHERE id = 1");
            }
            ExecutorService pool = Executors.newFixedThreadPool(n);
            CountDownLatch ready = new CountDownLatch(n), go = new CountDownLatch(1);
            List<Future<Worker>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return switch (m) {
                        case "UNSAFE" -> unsafe(u);
                        case "PESSIMISTIC" -> pessimistic(u);
                        case "OPTIMISTIC" -> optimistic(u);
                        default -> atomic(u);
                    };
                }));
            }
            ready.await();
            long t0 = System.nanoTime();
            go.countDown();
            int ok = 0, retries = 0;
            for (Future<Worker> f : futures) {
                Worker w = f.get(60, TimeUnit.SECONDS);
                if (w.success()) ok++;
                retries += w.retries();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            pool.shutdown();

            int finalStock;
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT stock FROM lab_inventory WHERE id = 1")) {
                rs.next();
                finalStock = rs.getInt(1);
            }
            int expected = start - ok * u;
            int oversold = Math.max(0, ok * u - start);
            boolean consistent = finalStock == expected && finalStock >= 0;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mode", m);
            out.put("threads", n);
            out.put("unitsPerAttempt", u);
            out.put("initialStock", start);
            out.put("succeeded", ok);
            out.put("rejected", n - ok);
            out.put("retries", retries);
            out.put("finalStock", finalStock);
            out.put("expectedStock", expected);
            out.put("oversoldUnits", oversold);
            out.put("lostUnits", Math.max(0, finalStock - expected));
            out.put("elapsedMs", ms);
            out.put("consistent", consistent);
            out.put("verdict", verdict(m, consistent, ok, u, start, finalStock, expected, oversold));
            return out;
        });
    }

    private String verdict(String m, boolean consistent, int ok, int u, int start, int finalStock, int expected, int oversold) {
        if (consistent) {
            return "Correct: " + ok + " buyers got " + (ok * u) + " units and stock fell from " + start + " to " + finalStock
                    + ". No update was lost and nothing was oversold.";
        }
        return "RACE CONDITION: " + ok + " buyers each believed they got " + u + " units (" + (ok * u) + " in total, "
                + oversold + " more than the " + start + " that existed), but stock only fell to " + finalStock
                + " instead of " + expected + ". Concurrent read-modify-write overwrote earlier updates (lost update).";
    }

    private Worker unsafe(int units) throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            int stock = readStock(c, "");
            Thread.sleep(THINK_MS);                                     // validation, pricing, AI call ...
            if (stock < units) { c.rollback(); return new Worker(false, 0); }
            try (PreparedStatement ps = c.prepareStatement("UPDATE lab_inventory SET stock = ? WHERE id = 1")) {
                ps.setInt(1, stock - units);                            // writes a value computed from a STALE read
                ps.executeUpdate();
            }
            c.commit();
            return new Worker(true, 0);
        }
    }

    private Worker pessimistic(int units) throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            int stock = readStock(c, " FOR UPDATE");                    // row lock held until COMMIT
            Thread.sleep(THINK_MS);
            if (stock < units) { c.rollback(); return new Worker(false, 0); }
            try (PreparedStatement ps = c.prepareStatement("UPDATE lab_inventory SET stock = stock - ? WHERE id = 1")) {
                ps.setInt(1, units);
                ps.executeUpdate();
            }
            c.commit();
            return new Worker(true, 0);
        }
    }

    private Worker optimistic(int units) throws Exception {
        int retries = 0;
        for (int attempt = 0; attempt < 200; attempt++) {
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                int stock; long version;
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT stock, version FROM lab_inventory WHERE id = 1")) {
                    rs.next();
                    stock = rs.getInt(1);
                    version = rs.getLong(2);
                }
                Thread.sleep(THINK_MS);
                if (stock < units) { c.rollback(); return new Worker(false, retries); }
                int changed;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE lab_inventory SET stock = stock - ?, version = version + 1 WHERE id = 1 AND version = ?")) {
                    ps.setInt(1, units);
                    ps.setLong(2, version);
                    changed = ps.executeUpdate();
                }
                if (changed == 1) { c.commit(); return new Worker(true, retries); }
                c.rollback();                                           // someone else won: re-read and retry
                retries++;
            }
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 6));
        }
        return new Worker(false, retries);
    }

    private Worker atomic(int units) throws Exception {
        Thread.sleep(THINK_MS);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE lab_inventory SET stock = stock - ? WHERE id = 1 AND stock >= ?")) {
            ps.setInt(1, units);
            ps.setInt(2, units);
            return new Worker(ps.executeUpdate() == 1, 0);              // autocommit: the check IS the update
        }
    }

    private int readStock(Connection c, String suffix) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT stock FROM lab_inventory WHERE id = 1" + suffix)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ======================================================================== isolation

    /**
     * T1 reads (A), T2 changes + inserts WITHOUT committing, T1 reads (B), T2 commits, T1 reads (C).
     * B differs from A -> dirty read.  C differs from A -> non-repeatable read / phantom.
     */
    public Map<String, Object> isolation(String level) {
        int jdbcLevel = switch (level.toUpperCase(Locale.ROOT)) {
            case "READ_UNCOMMITTED" -> Connection.TRANSACTION_READ_UNCOMMITTED;
            case "READ_COMMITTED" -> Connection.TRANSACTION_READ_COMMITTED;
            case "REPEATABLE_READ" -> Connection.TRANSACTION_REPEATABLE_READ;
            case "SERIALIZABLE" -> Connection.TRANSACTION_SERIALIZABLE;
            default -> throw new IllegalArgumentException("Unknown isolation level");
        };
        return exclusive(() -> {
            reset();
            Map<String, Object> out = new LinkedHashMap<>();
            List<Map<String, Object>> steps = new ArrayList<>();
            out.put("level", level.toUpperCase(Locale.ROOT));
            out.put("steps", steps);
            boolean t2Blocked = false;
            int stockA, countA, stockB, countB, stockC, countC;

            try (Connection t1 = ds.getConnection(); Connection t2 = ds.getConnection()) {
                t1.setTransactionIsolation(jdbcLevel);
                t1.setAutoCommit(false);
                t2.setAutoCommit(false);
                try (Statement s = t2.createStatement()) { s.execute("SET SESSION innodb_lock_wait_timeout = 2"); }

                stockA = readStock(t1, ""); countA = countRows(t1);
                steps.add(step("T1 reads", "stock = " + stockA + ", rows = " + countA, "baseline"));

                try (Statement s = t2.createStatement()) {
                    s.executeUpdate("UPDATE lab_inventory SET stock = stock + 50 WHERE id = 1");
                    s.executeUpdate("INSERT INTO lab_inventory (id, name, stock) VALUES (99, 'phantom', 1)");
                    steps.add(step("T2 changes stock (+50) and inserts a row", "not committed yet", "T2 holds its changes privately"));
                } catch (SQLException e) {
                    t2Blocked = true;
                    steps.add(step("T2 tries to change stock", "BLOCKED: lock wait timeout after 2 s",
                            "T1's reads took shared locks (SERIALIZABLE), so T2 must wait for T1"));
                    t2.rollback();
                }

                stockB = readStock(t1, ""); countB = countRows(t1);
                steps.add(step("T1 reads again (T2 still uncommitted)", "stock = " + stockB + ", rows = " + countB,
                        stockB != stockA ? "DIRTY READ: T1 saw data T2 never committed" : "no dirty read"));

                if (!t2Blocked) t2.commit();
                steps.add(step("T2 " + (t2Blocked ? "gave up" : "commits"), "", ""));

                stockC = readStock(t1, ""); countC = countRows(t1);
                steps.add(step("T1 reads a third time", "stock = " + stockC + ", rows = " + countC,
                        (stockC != stockA ? "NON-REPEATABLE READ (same row, different value). " : "")
                                + (countC != countA ? "PHANTOM READ (a new row appeared). " : "")
                                + (stockC == stockA && countC == countA ? "repeatable: T1 keeps its snapshot" : "")));
                t1.commit();
            }
            reset();

            out.put("dirtyRead", stockB != stockA);
            out.put("nonRepeatableRead", stockC != stockA);
            out.put("phantomRead", countC != countA);
            out.put("writerBlocked", t2Blocked);
            return out;
        });
    }

    private int countRows(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM lab_inventory")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void reset() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM lab_inventory WHERE id = 99");
            s.executeUpdate("UPDATE lab_inventory SET stock = 100, version = 0 WHERE id IN (1, 2)");
        }
    }

    private Map<String, Object> step(String what, String result, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("what", what);
        m.put("result", result);
        m.put("note", note);
        return m;
    }

    // ======================================================================== deadlock

    public Map<String, Object> deadlock(boolean ordered) {
        return exclusive(() -> {
            reset();
            CountDownLatch aHolds = new CountDownLatch(1), bHolds = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicInteger commitOrder = new AtomicInteger();
            Future<Map<String, Object>> fa = pool.submit(() -> txn("A", 1, 2, aHolds, bHolds, ordered, commitOrder));
            Future<Map<String, Object>> fb = pool.submit(() -> ordered
                    ? txn("B", 1, 2, bHolds, aHolds, true, commitOrder)         // same order as A
                    : txn("B", 2, 1, bHolds, aHolds, false, commitOrder));      // opposite order
            Map<String, Object> ra = fa.get(30, TimeUnit.SECONDS), rb = fb.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            reset();

            boolean deadlocked = Boolean.TRUE.equals(ra.get("deadlockVictim")) || Boolean.TRUE.equals(rb.get("deadlockVictim"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("lockOrder", ordered ? "ordered (both lock row 1 then row 2)" : "opposite (A: 1 then 2, B: 2 then 1)");
            out.put("deadlockDetected", deadlocked);
            out.put("transactions", List.of(ra, rb));
            out.put("explanation", ordered
                    ? "Both transactions request locks in the same global order, so one simply waits for the other. A circular wait is impossible. This is the rule CRAS follows when it locks several resources (ascending id)."
                    : (deadlocked
                        ? "Each transaction holds one row and waits for the row the other holds: a circular wait. InnoDB's deadlock detector chose one victim (error 1213) and rolled it back so the other could finish."
                        : "No deadlock occurred this time (timing). Run it again."));
            return out;
        });
    }

    private Map<String, Object> txn(String name, int first, int second, CountDownLatch iHold, CountDownLatch otherHolds,
                                    boolean ordered, AtomicInteger commitOrder) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("lockOrder", first + " then " + second);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) { s.execute("SET SESSION innodb_lock_wait_timeout = 5"); }
            lockRow(c, first);
            iHold.countDown();
            if (ordered) {
                Thread.sleep(300);                                     // hold the first lock for a while
            } else {
                otherHolds.await(5, TimeUnit.SECONDS);                  // wait until the other also holds one
                Thread.sleep(100);
            }
            lockRow(c, second);
            c.commit();
            r.put("outcome", "COMMITTED");
            r.put("commitPosition", commitOrder.incrementAndGet());
            r.put("deadlockVictim", false);
        } catch (SQLException e) {
            boolean dead = e.getErrorCode() == 1213 || "40001".equals(e.getSQLState());
            r.put("outcome", dead ? "ROLLED BACK (deadlock victim)" : "FAILED: " + e.getMessage());
            r.put("deadlockVictim", dead);
            if (dead) r.put("mysqlError", e.getMessage());
        } catch (Exception e) {
            r.put("outcome", "FAILED: " + e.getMessage());
            r.put("deadlockVictim", false);
        }
        return r;
    }

    private void lockRow(Connection c, int id) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT stock FROM lab_inventory WHERE id = " + id + " FOR UPDATE")) {
            rs.next();
        }
    }
}
