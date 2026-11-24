-- ============================================================================
-- CRAS Phase III: database objects (REFERENCE COPY)
-- The application installs all of this automatically on every start (DbmsBootstrap.java),
-- so you do NOT have to run this file. It exists so you can read it, show it in a viva,
-- or re-create the objects by hand in MySQL Workbench. Safe to run repeatedly.
-- Run order: start the backend once first, so Hibernate has created the base tables.
-- ============================================================================
USE cras_db;

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
            ) ENGINE=InnoDB;

            CREATE TABLE IF NOT EXISTS request_events (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                request_id  BIGINT      NOT NULL,
                old_status  VARCHAR(20) NULL,
                new_status  VARCHAR(20) NOT NULL,
                changed_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_events_request (request_id, changed_at)
            ) ENGINE=InnoDB;

            CREATE TABLE IF NOT EXISTS request_idempotency (
                idem_key     VARCHAR(80)  PRIMARY KEY,
                actor_email  VARCHAR(255) NOT NULL,
                request_id   BIGINT       NULL,
                created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB;

            CREATE TABLE IF NOT EXISTS lab_inventory (
                id       INT          PRIMARY KEY,
                name     VARCHAR(40)  NOT NULL,
                stock    INT          NOT NULL,
                version  BIGINT       NOT NULL DEFAULT 0
            ) ENGINE=InnoDB;
INSERT IGNORE INTO lab_inventory (id, name, stock) VALUES (1, 'Lab Water', 100), (2, 'Lab Food', 100);

DROP TRIGGER IF EXISTS trg_resources_no_negative;
DELIMITER $$
            CREATE TRIGGER trg_resources_no_negative BEFORE UPDATE ON resources
            FOR EACH ROW
            BEGIN
                IF NEW.available_quantity < 0 THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Stock cannot be negative';
                END IF;
            END$$
DELIMITER ;

DROP TRIGGER IF EXISTS trg_resources_ledger;
DELIMITER $$
            CREATE TRIGGER trg_resources_ledger AFTER UPDATE ON resources
            FOR EACH ROW
            BEGIN
                IF NEW.available_quantity <> OLD.available_quantity THEN
                    INSERT INTO stock_ledger (resource_id, delta, balance_after, reason, actor)
                    VALUES (NEW.id, NEW.available_quantity - OLD.available_quantity, NEW.available_quantity,
                            COALESCE(@cras_reason, 'DB_DIRECT'), COALESCE(@cras_actor, USER()));
                END IF;
            END$$
DELIMITER ;

DROP TRIGGER IF EXISTS trg_resources_ledger_insert;
DELIMITER $$
            CREATE TRIGGER trg_resources_ledger_insert AFTER INSERT ON resources
            FOR EACH ROW
            BEGIN
                INSERT INTO stock_ledger (resource_id, delta, balance_after, reason, actor)
                VALUES (NEW.id, NEW.available_quantity, NEW.available_quantity, 'OPENING',
                        COALESCE(@cras_actor, USER()));
            END$$
DELIMITER ;

DROP TRIGGER IF EXISTS trg_requests_history;
DELIMITER $$
            CREATE TRIGGER trg_requests_history AFTER UPDATE ON emergency_requests
            FOR EACH ROW
            BEGIN
                IF NEW.status <> OLD.status THEN
                    INSERT INTO request_events (request_id, old_status, new_status)
                    VALUES (NEW.id, OLD.status, NEW.status);
                END IF;
            END$$
DELIMITER ;

DROP TRIGGER IF EXISTS trg_requests_history_insert;
DELIMITER $$
            CREATE TRIGGER trg_requests_history_insert AFTER INSERT ON emergency_requests
            FOR EACH ROW
            BEGIN
                INSERT INTO request_events (request_id, old_status, new_status)
                VALUES (NEW.id, NULL, NEW.status);
            END$$
DELIMITER ;

DROP PROCEDURE IF EXISTS sp_allocate;
DELIMITER $$
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
            END$$
DELIMITER ;

            CREATE OR REPLACE VIEW v_priority_queue AS
            SELECT r.id AS request_id, res.name AS resource, r.priority_score, r.severity, r.quantity,
                   r.created_at, TIMESTAMPDIFF(MINUTE, r.created_at, NOW()) AS waiting_min,
                   ROW_NUMBER() OVER (PARTITION BY r.resource_id
                                      ORDER BY r.priority_score DESC, r.created_at ASC) AS queue_position
            FROM emergency_requests r
            JOIN resources res ON res.id = r.resource_id
            WHERE r.status = 'PENDING';

            CREATE OR REPLACE VIEW v_resource_pressure AS
            SELECT res.id AS resource_id, res.name, res.available_quantity, res.capacity,
                   ROUND(100 * (1 - res.available_quantity / NULLIF(res.capacity, 0)), 1) AS used_pct,
                   COALESCE(SUM(CASE WHEN r.status = 'PENDING' THEN r.quantity END), 0) AS pending_demand,
                   COUNT(CASE WHEN r.status = 'PENDING' THEN 1 END) AS pending_count
            FROM resources res
            LEFT JOIN emergency_requests r ON r.resource_id = res.id
            GROUP BY res.id, res.name, res.available_quantity, res.capacity;

            CREATE OR REPLACE VIEW v_ledger_check AS
            SELECT res.id AS resource_id, res.name, res.available_quantity AS stock,
                   COALESCE(SUM(l.delta), 0) AS ledger_sum,
                   res.available_quantity - COALESCE(SUM(l.delta), 0) AS drift
            FROM resources res
            LEFT JOIN stock_ledger l ON l.resource_id = res.id
            GROUP BY res.id, res.name, res.available_quantity;

            INSERT INTO stock_ledger (resource_id, delta, balance_after, reason, actor)
            SELECT v.resource_id, v.drift, v.stock, 'OPENING', 'bootstrap'
            FROM v_ledger_check v WHERE v.drift <> 0;

-- Indexes and the CHECK constraint (the app creates them only if missing):
CREATE INDEX idx_request_queue_v3 ON emergency_requests (resource_id, status, priority_score DESC, created_at ASC);
CREATE INDEX idx_request_requester ON emergency_requests (requester_email);
CREATE INDEX idx_request_created ON emergency_requests (created_at);
ALTER TABLE resources ADD CONSTRAINT chk_stock_nonneg CHECK (available_quantity >= 0);
-- (If an index/constraint already exists MySQL reports a duplicate: that is fine, ignore it.)
