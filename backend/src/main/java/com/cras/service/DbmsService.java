package com.cras.service;

import com.cras.config.DbmsBootstrap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/**
 * Read-only DBMS insight: what is installed, ledger reconciliation, EXPLAIN plans, and analytics
 * written with MySQL 8 window functions and a recursive CTE. Nothing here accepts SQL from the
 * client: every statement is fixed text, so there is no injection surface.
 */
@Service
public class DbmsService {

    private final JdbcTemplate jdbc;
    private final DbmsBootstrap bootstrap;

    public DbmsService(JdbcTemplate jdbc, DbmsBootstrap bootstrap) {
        this.jdbc = jdbc;
        this.bootstrap = bootstrap;
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> rows(String sql, Object... params) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, params)) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Timestamp t) v = t.toLocalDateTime().toString();
                else if (v instanceof LocalDateTime t) v = t.toString();
                copy.put(e.getKey(), v);
            }
            out.add(copy);
        }
        return out;
    }

    /** One analytics block; a failing query reports its error instead of breaking the whole page. */
    private Map<String, Object> section(String title, String concept, String sql, Supplier<List<Map<String, Object>>> run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("concept", concept);
        m.put("sql", sql.strip());
        try {
            m.put("rows", run.get());
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            m.put("rows", List.of());
            m.put("error", String.valueOf(root.getMessage()));
        }
        return m;
    }

    // ------------------------------------------------------------------ installed objects

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bootstrap", bootstrap.getReport());
        m.put("database", jdbc.queryForObject("SELECT DATABASE()", String.class));
        m.put("version", jdbc.queryForObject("SELECT VERSION()", String.class));
        m.put("isolation", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        m.put("triggers", rows("SELECT TRIGGER_NAME AS name, ACTION_TIMING AS timing, EVENT_MANIPULATION AS event, "
                + "EVENT_OBJECT_TABLE AS tbl FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() ORDER BY TRIGGER_NAME"));
        m.put("routines", rows("SELECT ROUTINE_NAME AS name, ROUTINE_TYPE AS type FROM information_schema.ROUTINES "
                + "WHERE ROUTINE_SCHEMA = DATABASE() ORDER BY ROUTINE_NAME"));
        m.put("views", rows("SELECT TABLE_NAME AS name FROM information_schema.VIEWS WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME"));
        m.put("constraints", rows("SELECT CONSTRAINT_NAME AS name, CONSTRAINT_TYPE AS type, TABLE_NAME AS tbl "
                + "FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_TYPE IN ('CHECK','FOREIGN KEY','UNIQUE') ORDER BY TABLE_NAME, CONSTRAINT_NAME"));
        return m;
    }

    // ------------------------------------------------------------------ ledger

    public List<Map<String, Object>> ledger(Long resourceId, int limit) {
        int lim = Math.max(1, Math.min(200, limit));
        String base = "SELECT l.id, r.name AS resource, l.delta, l.balance_after AS balanceAfter, l.reason, l.actor, "
                + "DATE_FORMAT(l.created_at, '%Y-%m-%d %H:%i:%s') AS at FROM stock_ledger l "
                + "JOIN resources r ON r.id = l.resource_id ";
        if (resourceId == null) return rows(base + "ORDER BY l.id DESC LIMIT ?", lim);
        return rows(base + "WHERE l.resource_id = ? ORDER BY l.id DESC LIMIT ?", resourceId, lim);
    }

    /** Invariant: stock == SUM(ledger deltas). Any difference means stock changed without a ledger entry. */
    public Map<String, Object> reconcile() {
        List<Map<String, Object>> list = rows("SELECT res.id AS resourceId, res.name, res.available_quantity AS stock, "
                + "COALESCE(SUM(l.delta), 0) AS ledgerSum, res.available_quantity - COALESCE(SUM(l.delta), 0) AS drift "
                + "FROM resources res LEFT JOIN stock_ledger l ON l.resource_id = res.id "
                + "GROUP BY res.id, res.name, res.available_quantity ORDER BY res.id");
        boolean ok = list.stream().allMatch(r -> ((Number) r.get("drift")).longValue() == 0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("balanced", ok);
        m.put("rows", list);
        return m;
    }

    // ------------------------------------------------------------------ EXPLAIN

    public Map<String, Object> explain(String which, Long resourceId) {
        long rid = resourceId != null ? resourceId : firstResourceId();
        String sql, tip;
        Object[] params = new Object[0];
        switch (which) {
            case "queue" -> {
                sql = "SELECT id, priority_score FROM emergency_requests WHERE resource_id = ? AND status = 'PENDING' "
                        + "ORDER BY priority_score DESC, created_at ASC";
                params = new Object[]{rid};
                tip = "The composite index (resource_id, status, priority_score DESC, created_at ASC) matches the "
                        + "WHERE and ORDER BY exactly: expect type=ref, key=idx_request_queue_v3 and NO 'Using filesort'.";
            }
            case "ledger" -> {
                sql = "SELECT * FROM stock_ledger WHERE resource_id = ? ORDER BY created_at DESC LIMIT 20";
                params = new Object[]{rid};
                tip = "idx_ledger_resource_time (resource_id, created_at) serves the filter and the ordering.";
            }
            case "scan" -> {
                sql = "SELECT id FROM emergency_requests WHERE DATE(created_at) = CURDATE()";
                tip = "Anti-pattern: wrapping the column in a function (DATE) hides it from the index, so MySQL "
                        + "must read every row (type=ALL).";
            }
            case "sargable" -> {
                sql = "SELECT id FROM emergency_requests WHERE created_at >= CURDATE() AND created_at < CURDATE() + INTERVAL 1 DAY";
                tip = "Same business question written as a RANGE on the bare column: the index idx_request_created "
                        + "can be used (type=range). On a tiny table the optimiser may still choose a scan; the "
                        + "pattern is what matters.";
            }
            default -> throw new IllegalArgumentException("Unknown plan: " + which);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("which", which);
        m.put("sql", sql);
        m.put("tip", tip);
        m.put("plan", rows("EXPLAIN " + sql, params));
        return m;
    }

    public List<Map<String, Object>> indexes() {
        return rows("SELECT TABLE_NAME AS tbl, INDEX_NAME AS name, SEQ_IN_INDEX AS pos, COLUMN_NAME AS col, "
                + "NON_UNIQUE AS nonUnique, CARDINALITY AS cardinality FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('emergency_requests','stock_ledger','allocations') "
                + "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX");
    }

    private long firstResourceId() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM resources ORDER BY id LIMIT 1", Long.class);
        return ids.isEmpty() ? 0 : ids.get(0);
    }

    // ------------------------------------------------------------------ views (whitelist)

    private static final Set<String> VIEWS = Set.of("v_priority_queue", "v_resource_pressure", "v_ledger_check");

    public List<Map<String, Object>> viewRows(String name) {
        if (!VIEWS.contains(name)) throw new IllegalArgumentException("Unknown view");
        return rows("SELECT * FROM " + name + " LIMIT 100");
    }

    // ------------------------------------------------------------------ analytics

    public List<Map<String, Object>> analytics() {
        LocalDateTime end = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime start = end.minusHours(23);
        List<Map<String, Object>> out = new ArrayList<>();

        String hourly = """
            WITH RECURSIVE hours (h) AS (
                SELECT CAST(? AS DATETIME)
                UNION ALL
                SELECT h + INTERVAL 1 HOUR FROM hours WHERE h < ?
            )
            SELECT DATE_FORMAT(hours.h, '%H:00') AS hour,
                   COUNT(r.id) AS requests,
                   COALESCE(SUM(CASE WHEN r.severity >= 8 THEN 1 ELSE 0 END), 0) AS critical
            FROM hours
            LEFT JOIN emergency_requests r
                   ON r.created_at >= hours.h AND r.created_at < hours.h + INTERVAL 1 HOUR
            GROUP BY hours.h
            ORDER BY hours.h""";
        out.add(section("Request volume, last 24 hours",
                "Recursive CTE generates the 24 hourly buckets so hours with zero requests still appear (gap filling).",
                hourly, () -> rows(hourly, start, end)));

        String projection = """
            SELECT q.id, q.resource, q.priority_score AS priority, q.severity, q.quantity, q.rnk AS rank_in_queue,
                   q.cum_demand AS cumulative_demand, q.stock,
                   CASE WHEN q.cum_demand <= q.stock THEN 'FITS' ELSE 'SHORT' END AS projection
            FROM (
                SELECT r.id, res.name AS resource, r.priority_score, r.severity, r.quantity,
                       res.available_quantity AS stock,
                       RANK() OVER (PARTITION BY r.resource_id
                                    ORDER BY r.priority_score DESC, r.created_at) AS rnk,
                       SUM(r.quantity) OVER (PARTITION BY r.resource_id
                                    ORDER BY r.priority_score DESC, r.created_at
                                    ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_demand
                FROM emergency_requests r
                JOIN resources res ON res.id = r.resource_id
                WHERE r.status = 'PENDING'
            ) q
            ORDER BY q.resource, q.rnk
            LIMIT 40""";
        out.add(section("Who will be served before stock runs out",
                "Window functions: RANK() and a running SUM() per resource, in priority order. 'FITS' means the cumulative demand up to that request is still covered by stock.",
                projection, () -> rows(projection)));

        String bands = """
            SELECT CASE WHEN r.severity >= 8 THEN 'Critical (8-10)'
                        WHEN r.severity >= 5 THEN 'High (5-7)'
                        ELSE 'Routine (1-4)' END AS band,
                   COUNT(*) AS allocations,
                   ROUND(AVG(TIMESTAMPDIFF(MINUTE, r.created_at, a.allocated_at)), 1) AS avg_wait_min,
                   MAX(TIMESTAMPDIFF(MINUTE, r.created_at, a.allocated_at)) AS max_wait_min
            FROM allocations a
            JOIN emergency_requests r ON r.id = a.request_id
            GROUP BY band
            ORDER BY MIN(r.severity) DESC""";
        out.add(section("Response time by severity band",
                "Join + conditional grouping + aggregate: does the scheduler really serve critical requests first?",
                bands, () -> rows(bands)));

        String pressure = """
            SELECT * FROM v_resource_pressure ORDER BY used_pct DESC""";
        out.add(section("Resource pressure (view)",
                "A view hides a LEFT JOIN + conditional aggregation behind a single name.",
                pressure, () -> rows(pressure)));

        String status = "SELECT status, COUNT(*) AS requests FROM emergency_requests GROUP BY status ORDER BY requests DESC";
        out.add(section("Requests by status", "GROUP BY aggregation.", status, () -> rows(status)));
        return out;
    }
}
