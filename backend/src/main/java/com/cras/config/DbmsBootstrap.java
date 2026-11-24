package com.cras.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs the DBMS-level safety net on every start-up. Idempotent: safe to run again and again.
 *
 *  - stock_ledger         immutable double-entry style stock movement log
 *  - trg_resources_*      triggers: refuse negative stock, write the ledger, even for manual SQL
 *  - chk_stock_nonneg     CHECK constraint (second line of defence)
 *  - trg_requests_history request status history (powers the request timeline)
 *  - sp_allocate          the allocation transaction written as a stored procedure
 *  - v_* views            queue, resource pressure and ledger reconciliation
 *  - composite indexes    support the queue query (see EXPLAIN in the DBMS Lab)
 *  - request_idempotency  makes POST /api/requests safe to retry (Idempotency-Key header)
 *  - lab_inventory        scratch table for the concurrency experiments (never touches real stock)
 *
 * Every step is isolated: if one fails (for example a MySQL privilege problem) the application
 * still starts and the DBMS Lab "Schema objects" tab shows exactly which step failed and why.
 */
@Component
public class DbmsBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DbmsBootstrap.class);

    private final JdbcTemplate jdbc;
    private final List<Map<String, String>> report = new ArrayList<>();

    public DbmsBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public synchronized List<Map<String, String>> getReport() {
        return Collections.unmodifiableList(new ArrayList<>(report));
    }

    public synchronized void install() {
        report.clear();

        run("stock_ledger", "TABLE", """
            CREATE TABLE IF NOT EXISTS stock_ledger (
                id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                resource_id    BIGINT       NOT NULL,
                delta          INT          NOT NULL,
                balance_after  INT          NOT NULL,
                reason         VARCHAR(40)  NOT NULL,
                actor          VARCHAR(255) NULL,
                created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_ledger_resource FOREIGN KEY (resource_id) REFERENCES resources(id),
                INDEX idx_ledger_resource_time (resource_id, created_at)
            ) ENGINE=InnoDB""");

        run("request_events", "TABLE", """
            CREATE TABLE IF NOT EXISTS request_events (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                request_id  BIGINT      NOT NULL,
                old_status  VARCHAR(20) NULL,
                new_status  VARCHAR(20) NOT NULL,
                changed_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_events_request (request_id, changed_at)
            ) ENGINE=InnoDB""");

        run("request_idempotency", "TABLE", """
            CREATE TABLE IF NOT EXISTS request_idempotency (
                idem_key     VARCHAR(80)  PRIMARY KEY,
                actor_email  VARCHAR(255) NOT NULL,
                request_id   BIGINT       NULL,
                created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB""");

        run("lab_inventory", "TABLE", """
            CREATE TABLE IF NOT EXISTS lab_inventory (
                id       INT          PRIMARY KEY,
                name     VARCHAR(40)  NOT NULL,
                stock    INT          NOT NULL,
                version  BIGINT       NOT NULL DEFAULT 0
            ) ENGINE=InnoDB""");
        run("lab_inventory seed rows", "DATA",
            "INSERT IGNORE INTO lab_inventory (id, name, stock) VALUES (1, 'Lab Water', 100), (2, 'Lab Food', 100)");

        // CHECK constraint (MySQL 8.0.16+ enforces it)
        if (!exists("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'resources' AND CONSTRAINT_NAME = 'chk_stock_nonneg'")) {
            run("chk_stock_nonneg", "CONSTRAINT",
                "ALTER TABLE resources ADD CONSTRAINT chk_stock_nonneg CHECK (available_quantity >= 0)");
        } else {
            ok("chk_stock_nonneg", "CONSTRAINT");
        }

        // Composite + descending index that matches the scheduler's exact query:
        //   WHERE resource_id = ? AND status = 'PENDING' ORDER BY priority_score DESC, created_at ASC
        index("idx_request_queue_v3", "emergency_requests",
                "(resource_id, status, priority_score DESC, created_at ASC)");
        index("idx_request_requester", "emergency_requests", "(requester_email)");
        index("idx_request_created", "emergency_requests", "(created_at)");

        // ---- triggers (dropped and recreated so that new logic always replaces old logic) ----
        trigger("trg_resources_no_negative", """
            CREATE TRIGGER trg_resources_no_negative BEFORE UPDATE ON resources
            FOR EACH ROW
            BEGIN
                IF NEW.available_quantity < 0 THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Stock cannot be negative';
                END IF;
            END""");

        trigger("trg_resources_ledger", """
            CREATE TRIGGER trg_resources_ledger AFTER UPDATE ON resources
            FOR EACH ROW
            BEGIN
                IF NEW.available_quantity <> OLD.available_quantity THEN
                    INSERT INTO stock_ledger (resource_id, delta, balance_after, reason, actor)
                    VALUES (NEW.id, NEW.available_quantity - OLD.available_quantity, NEW.available_quantity,
                            COALESCE(@cras_reason, 'DB_DIRECT'), COALESCE(@cras_actor, USER()));
                END IF;
            END""");

        trigger("trg_resources_ledger_insert", """
            CREATE TRIGGER trg_resources_ledger_insert AFTER INSERT ON resources
            FOR EACH ROW
            BEGIN
                INSERT INTO stock_ledger (resource_id, delta, balance_after, reason, actor)
                VALUES (NEW.id, NEW.available_quantity, NEW.available_quantity, 'OPENING',
                        COALESCE(@cras_actor, USER()));
            END""");

        trigger("trg_requests_history", """
            CREATE TRIGGER trg_requests_history AFTER UPDATE ON emergency_requests
            FOR EACH ROW
            BEGIN
                IF NEW.status <> OLD.status THEN
                    INSERT INTO request_events (request_id, old_status, new_status)
                    VALUES (NEW.id, OLD.status, NEW.status);
                END IF;
            END""");

        trigger("trg_requests_history_insert", """
            CREATE TRIGGER trg_requests_history_insert AFTER INSERT ON emergency_requests
            FOR EACH ROW
            BEGIN
                INSERT INTO request_events (request_id, old_status, new_status)
                VALUES (NEW.id, NULL, NEW.status);
            END""");

        // ---- stored procedure ----
        run("sp_allocate drop", "PROCEDURE", "DROP PROCEDURE IF EXISTS sp_allocate");
        run("sp_allocate", "PROCEDURE", """
            CREATE PROCEDURE sp_allocate(IN p_request_id BIGINT, IN p_actor VARCHAR(255))
            BEGIN
                DECLARE v_res    BIGINT;
                DECLARE v_qty    INT;
                DECLARE v_status VARCHAR(20);
                DECLARE v_avail  INT;
                DECLARE EXIT HANDLER FOR SQLEXCEPTION
                BEGIN
                    ROLLBACK;
                    RESIGNAL;
                END;

                START TRANSACTION;
                SELECT resource_id, quantity, status INTO v_res, v_qty, v_status
                  FROM emergency_requests WHERE id = p_request_id FOR UPDATE;
                IF v_res IS NULL THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Request not found';
                END IF;
                IF v_status NOT IN ('PENDING', 'PREEMPTED') THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Request is not waiting for allocation';
                END IF;
                SELECT available_quantity INTO v_avail FROM resources WHERE id = v_res FOR UPDATE;
                IF v_avail < v_qty THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Insufficient stock';
                END IF;

                SET @cras_actor = p_actor;
                SET @cras_reason = 'ALLOCATE_SP';
                UPDATE resources SET available_quantity = available_quantity - v_qty WHERE id = v_res;
                UPDATE emergency_requests SET status = 'ALLOCATED' WHERE id = p_request_id;
                INSERT INTO allocations (request_id, resource_id, quantity, status, allocated_at)
                VALUES (p_request_id, v_res, v_qty, 'ACTIVE', NOW(6));
                SET @cras_actor = NULL;
                SET @cras_reason = NULL;
                COMMIT;
            END""");

        // ---- views ----
        run("v_priority_queue", "VIEW", """
            CREATE OR REPLACE VIEW v_priority_queue AS
            SELECT r.id AS request_id, res.name AS resource, r.priority_score, r.severity, r.quantity,
                   r.created_at, TIMESTAMPDIFF(MINUTE, r.created_at, NOW()) AS waiting_min,
                   ROW_NUMBER() OVER (PARTITION BY r.resource_id
                                      ORDER BY r.priority_score DESC, r.created_at ASC) AS queue_position
            FROM emergency_requests r
            JOIN resources res ON res.id = r.resource_id
            WHERE r.status = 'PENDING'""");

        run("v_resource_pressure", "VIEW", """
            CREATE OR REPLACE VIEW v_resource_pressure AS
            SELECT res.id AS resource_id, res.name, res.available_quantity, res.capacity,
                   ROUND(100 * (1 - res.available_quantity / NULLIF(res.capacity, 0)), 1) AS used_pct,
                   COALESCE(SUM(CASE WHEN r.status = 'PENDING' THEN r.quantity END), 0) AS pending_demand,
                   COUNT(CASE WHEN r.status = 'PENDING' THEN 1 END) AS pending_count
            FROM resources res
            LEFT JOIN emergency_requests r ON r.resource_id = res.id
            GROUP BY res.id, res.name, res.available_quantity, res.capacity""");

        run("v_ledger_check", "VIEW", """
            CREATE OR REPLACE VIEW v_ledger_check AS
            SELECT res.id AS resource_id, res.name, res.available_quantity AS stock,
                   COALESCE(SUM(l.delta), 0) AS ledger_sum,
                   res.available_quantity - COALESCE(SUM(l.delta), 0) AS drift
            FROM resources res
            LEFT JOIN stock_ledger l ON l.resource_id = res.id
            GROUP BY res.id, res.name, res.available_quantity""");

        // Make the ledger consistent with stock that existed BEFORE the triggers were installed
        run("ledger opening balances", "DATA", """
            INSERT INTO stock_ledger (resource_id, delta, balance_after, reason, actor)
            SELECT v.resource_id, v.drift, v.stock, 'OPENING', 'bootstrap'
            FROM v_ledger_check v WHERE v.drift <> 0""");

        long failed = report.stream().filter(m -> "FAILED".equals(m.get("status"))).count();
        log.info("[dbms] bootstrap finished: {} steps, {} failed", report.size(), failed);
    }

    // ----------------------------------------------------------------------------------------

    private void trigger(String name, String createSql) {
        run(name + " drop", "TRIGGER", "DROP TRIGGER IF EXISTS " + name);
        run(name, "TRIGGER", createSql);
    }

    private void index(String name, String table, String columns) {
        String check = "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = '" + table + "' AND INDEX_NAME = '" + name + "'";
        if (exists(check)) {
            ok(name, "INDEX");
        } else {
            run(name, "INDEX", "CREATE INDEX " + name + " ON " + table + " " + columns);
        }
    }

    private boolean exists(String countSql) {
        try {
            Integer n = jdbc.queryForObject(countSql, Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void ok(String name, String kind) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("kind", kind);
        m.put("status", "OK");
        m.put("error", "");
        report.add(m);
    }

    private void run(String name, String kind, String sql) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("kind", kind);
        try {
            jdbc.execute(sql);
            m.put("status", "OK");
            m.put("error", "");
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            m.put("status", "FAILED");
            m.put("error", String.valueOf(root.getMessage()));
            log.warn("[dbms] step '{}' failed: {}", name, root.getMessage());
        }
        report.add(m);
    }
}
