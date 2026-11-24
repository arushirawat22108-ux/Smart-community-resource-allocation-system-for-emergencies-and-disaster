-- CRAS Phase III: live DBMS demos for the evaluator (MySQL 8, InnoDB).
-- Open TWO MySQL Workbench / terminal sessions and run the lines marked [S1] and [S2]
-- in the order shown. Not executed by the author of this file: rehearse once before the viva.

USE cras_db;

-- =================================================================================
-- DEMO 1: LOST UPDATE (no lock)  vs  FOR UPDATE (pessimistic lock)
-- =================================================================================
-- Setup (either session):
--   UPDATE resources SET available_quantity = 10 WHERE name = 'Medical Kit';

-- ---- 1A. Without a lock: both sessions read 10, both write, one change is LOST ----
-- [S1] START TRANSACTION;
-- [S1] SELECT available_quantity FROM resources WHERE name = 'Medical Kit';      -- 10
-- [S2] START TRANSACTION;
-- [S2] SELECT available_quantity FROM resources WHERE name = 'Medical Kit';      -- 10
-- [S1] UPDATE resources SET available_quantity = 10 - 4 WHERE name = 'Medical Kit';  -- app computed 6
-- [S1] COMMIT;
-- [S2] UPDATE resources SET available_quantity = 10 - 3 WHERE name = 'Medical Kit';  -- app computed 7
-- [S2] COMMIT;
-- Result: stock = 7, but 7 units were handed out. 3 units appear from nowhere: a LOST UPDATE.

-- ---- 1B. With SELECT ... FOR UPDATE: the second session WAITS and reads fresh data ----
-- [S1] START TRANSACTION;
-- [S1] SELECT available_quantity FROM resources WHERE name = 'Medical Kit' FOR UPDATE;   -- 10, row locked
-- [S2] START TRANSACTION;
-- [S2] SELECT available_quantity FROM resources WHERE name = 'Medical Kit' FOR UPDATE;   -- BLOCKS...
-- [S1] UPDATE resources SET available_quantity = available_quantity - 4 WHERE name = 'Medical Kit';
-- [S1] COMMIT;                                                                           -- S2 now unblocks, reads 6
-- [S2] UPDATE resources SET available_quantity = available_quantity - 3 WHERE name = 'Medical Kit';
-- [S2] COMMIT;
-- Result: stock = 3. Correct. This is exactly what AllocationService.allocate() does.

-- =================================================================================
-- DEMO 2: ISOLATION LEVELS (non-repeatable read)
-- =================================================================================
-- ---- READ COMMITTED: a second read inside one transaction can see new data ----
-- [S1] SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
-- [S1] START TRANSACTION;
-- [S1] SELECT available_quantity FROM resources WHERE name = 'Water';                    -- e.g. 1000
-- [S2] UPDATE resources SET available_quantity = 900 WHERE name = 'Water';  COMMIT;
-- [S1] SELECT available_quantity FROM resources WHERE name = 'Water';                    -- 900  (changed!)
-- [S1] COMMIT;
-- ---- REPEATABLE READ (MySQL default): the snapshot stays the same inside the transaction ----
-- [S1] SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
-- [S1] START TRANSACTION;
-- [S1] SELECT available_quantity FROM resources WHERE name = 'Water';                    -- 900
-- [S2] UPDATE resources SET available_quantity = 800 WHERE name = 'Water';  COMMIT;
-- [S1] SELECT available_quantity FROM resources WHERE name = 'Water';                    -- still 900
-- [S1] SELECT available_quantity FROM resources WHERE name = 'Water' FOR UPDATE;         -- 800 (locking read sees latest)
-- [S1] COMMIT;
-- Talking point: that last line is why the allocation uses FOR UPDATE: it reads the latest committed value.

-- =================================================================================
-- DEMO 3: DEADLOCK (opposite lock order) and how InnoDB resolves it
-- =================================================================================
-- [S1] START TRANSACTION;
-- [S1] SELECT * FROM resources WHERE name = 'Water' FOR UPDATE;
-- [S2] START TRANSACTION;
-- [S2] SELECT * FROM resources WHERE name = 'Food Packet' FOR UPDATE;
-- [S1] SELECT * FROM resources WHERE name = 'Food Packet' FOR UPDATE;                    -- waits for S2
-- [S2] SELECT * FROM resources WHERE name = 'Water' FOR UPDATE;
--      -> ERROR 1213 (40001): Deadlock found when trying to get lock; try restarting transaction
--      InnoDB rolls back ONE victim; the other continues. Inspect with:
--      SHOW ENGINE INNODB STATUS\G      (section "LATEST DETECTED DEADLOCK")
-- Prevention rule used in CRAS: lock rows in a fixed order (ascending resource id) and
-- lock only ONE resource row per allocation. Multi-resource kits must lock in id order.

-- =================================================================================
-- DEMO 4: INDEXING with EXPLAIN (before / after)
-- =================================================================================
-- Phase II index: idx_request_queue_v3 (resource_id, status, priority_score DESC, created_at ASC)
EXPLAIN SELECT id, priority_score, created_at
  FROM emergency_requests
 WHERE resource_id = 1 AND status = 'PENDING'
 ORDER BY priority_score DESC, created_at ASC;
-- Look at: key = idx_request_queue, no "Using filesort" if the index order matches the sort.

-- Compare with a query that cannot use the index well (function on a column):
EXPLAIN SELECT id FROM emergency_requests
 WHERE DATE(created_at) = CURDATE();
-- Better: WHERE created_at >= CURDATE() AND created_at < CURDATE() + INTERVAL 1 DAY  (uses idx_request_created)

-- EXPLAIN ANALYZE (MySQL 8.0.18+) shows real timings:
-- EXPLAIN ANALYZE SELECT ... ;

-- =================================================================================
-- DEMO 5: ANALYTICS (window functions, CTEs)
-- =================================================================================
-- Rank inside each resource queue (what the scheduler does, expressed in SQL)
SELECT resource_id, id AS request_id, priority_score,
       ROW_NUMBER() OVER (PARTITION BY resource_id
                          ORDER BY priority_score DESC, created_at ASC) AS queue_position
  FROM emergency_requests
 WHERE status = 'PENDING';

-- Starvation watch: pending longer than 4 hours
WITH waiting AS (
    SELECT id, resource_id, priority_score,
           TIMESTAMPDIFF(MINUTE, created_at, NOW()) AS waiting_minutes
      FROM emergency_requests WHERE status = 'PENDING'
)
SELECT * FROM waiting WHERE waiting_minutes > 240 ORDER BY waiting_minutes DESC;

-- Average time to allocation per resource (from the request_events history table)
SELECT res.name,
       ROUND(AVG(TIMESTAMPDIFF(MINUTE, r.created_at, e.changed_at)), 1) AS avg_minutes_to_allocation,
       COUNT(*) AS allocated_requests
  FROM request_events e
  JOIN emergency_requests r ON r.id = e.request_id
  JOIN resources res ON res.id = r.resource_id
 WHERE e.new_status = 'ALLOCATED'
 GROUP BY res.name;

-- Stock timeline from the immutable ledger (running balance)
SELECT resource_id, created_at, delta, reason,
       SUM(delta) OVER (PARTITION BY resource_id ORDER BY created_at, id) AS net_movement_so_far
  FROM stock_ledger
 ORDER BY resource_id, created_at;

-- =================================================================================
-- DEMO 6: DURABILITY / RECOVERY (commands, run in a shell)
-- =================================================================================
--   mysqldump -u root -p --single-transaction --routines --triggers cras_db > cras_backup.sql
--   (restore)  mysql -u root -p cras_db < cras_backup.sql
-- Crash test: start a transaction, do an UPDATE without COMMIT, kill the mysqld process,
-- restart it, and show the uncommitted change is gone (atomicity) while committed rows
-- survive (durability) because InnoDB replays its redo log.
