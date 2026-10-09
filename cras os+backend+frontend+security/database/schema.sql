CREATE DATABASE IF NOT EXISTS cras_db;
USE cras_db;

-- Spring Boot/JPA creates and updates the relational tables.
-- Useful DBMS demo queries:

SHOW TABLES;

SELECT * FROM users;
SELECT * FROM locations;
SELECT * FROM resources;
SELECT * FROM emergency_requests;
SELECT * FROM allocations;
SELECT * FROM audit_logs;

-- Priority queue:
SELECT id, resource_id, quantity, severity, affected_people,
       scarcity, priority_score, status, created_at
FROM emergency_requests
WHERE status = 'PENDING'
ORDER BY priority_score DESC, created_at ASC;

-- Resource inventory:
SELECT id, name, unit, available_quantity
FROM resources
ORDER BY name;
