# Viva guide: what to show, what to say

A suggested 12-minute path, then a concept map, likely questions, and the limitations to mention yourself.

## Demo path

1. **Overview** (1 min). Stock, queue counters, the live feed. Create a request in another tab and watch it appear (Server-Sent Events).
2. **Request Help** (1 min). Submit a request; double-click the button. Only one request exists (idempotency key).
3. **Priority Queue** (1 min). Priority, the aging bar, the *starving* flag. Preempt a critical request.
4. **Manager Desk → Autopilot** (1 min). Engage it and create a few requests: the daemon dispatches them on its own thread. "Run one tick" shows a single scheduler pass.
5. **OS Lab → Scheduler simulator** (2 min). Run *Mixed burst* (convoy effect, urgency), then *Steady urgent stream* (starvation). Read the generated "What this run shows".
6. **OS Lab → Banker's algorithm** (2 min). *Run stress demo*: watch "not enough stock" and "UNSAFE" refusals.
7. **DBMS Lab → Concurrency** (2 min). *Compare all four*: UNSAFE oversells, the others are correct. Then *Compare all levels* and the deadlock demo.
8. **DBMS Lab → Integrity** (1 min). In MySQL Workbench run the `UPDATE` shown on the page; press *Re-check*: the ledger recorded it, the books balance. Try a negative value: the trigger refuses it.
9. **Audit Ledger** (1 min, admin). *Verify the chain*; tamper with a row in Workbench; verify again: the exact entry is reported.

## Concept map

| Feature | Concept | Where in the code | One-line explanation |
|---|---|---|---|
| Priority + aging | Priority scheduling, starvation | `PriorityService` | Waiting time adds up to +15 points, so nothing waits forever. |
| Preemption | Preemptive scheduling | `AllocationService.preemptFor` | A critical request takes resources from a lower-priority allocation, which returns to the queue. |
| Backfilling, critical reservation | Backfilling | `SchedulerService` | Small requests fill gaps without delaying the head of the queue; some stock is reserved for critical needs. |
| Scheduler simulator | Policy comparison, convoy effect, fairness | `os/SchedulerSimulator` | Same workload, six policies, one tick = one minute. |
| Banker's algorithm | Deadlock avoidance, safe state | `os/BankerAlgorithm`, `BankerService` | Grant only if a safe completion order still exists. |
| Resource ordering | Deadlock prevention (no circular wait) | `ResourceRepository.lockAllOrdered` | Always lock resources in ascending id. |
| Autopilot | Dispatcher loop, background thread | `AutopilotService` | `@Scheduled` loop competing with manual actions for locked rows. |
| Process states | Process lifecycle | OS Lab → Process states | PENDING = ready, ALLOCATED = running, COMPLETED = terminated. |
| Pessimistic locking | Row locks, `SELECT … FOR UPDATE` | `AllocationService`, lab | Serialises access to one row. |
| Optimistic locking | Version check + retry | lab | Let everyone try; losers retry. |
| Isolation levels | ACID "I", anomalies | lab | READ UNCOMMITTED … SERIALIZABLE, observed live. |
| Deadlock | Circular wait, victim selection | lab | InnoDB rolls back one transaction (error 1213). |
| Triggers + ledger | Integrity inside the DB, double-entry idea | `DbmsBootstrap` | No path, even manual SQL, can change stock unrecorded. |
| CHECK constraint | Declarative integrity | `chk_stock_nonneg` | Second line of defence behind the trigger. |
| Stored procedure | Server-side transaction | `sp_allocate` | The allocation logic as one atomic DB routine. |
| Views, window functions, CTE | Advanced SQL | `DbmsService.analytics` | `RANK`, running `SUM`, recursive CTE for gap filling. |
| Composite index + EXPLAIN | Query optimisation, sargability | Query plans tab | The index matches `WHERE` and `ORDER BY`; a function on the column defeats it. |
| Idempotency key | Exactly-once semantics | `RequestService.create` | Unique key claimed first; concurrent duplicates block, then return the original. |
| Audit hash chain | Tamper evidence, hashing | `AuditHasher`, `AuditService` | Each entry hashes its content plus the previous hash. |
| After-commit events | Transaction synchronisation | `RealtimeService` | Clients never see a change that later rolls back. |
| Automatic severity | Rule-based estimation, explainability | `SeverityService` | Severity comes from known facts plus yes/no answers, with reasons shown and a logged manager override. |
| Login throttle, RBAC | Security | `LoginThrottle`, `RequestService` | Lockout after repeated failures; community users see masked identities. |

## Questions you may be asked

* **Why pessimistic locking for allocation?** Contention on one scarce resource is high, and the decision also reads related rows; optimistic retries would thrash. The lab shows the trade-off.
* **Banker's vs ordered locking?** Different problems. Ordered locking *prevents* deadlock between database transactions. Banker's *avoids* resource deadlock between camps by refusing unsafe grants.
* **Why can aging not reduce the maximum wait in the mixed-burst demo?** In a finite batch, the last job always waits for the total work. Aging helps when urgent work *keeps arriving*, which is why the stream workload exists.
* **Why a trigger when the application already checks stock?** The application is not the only writer. The trigger and constraint enforce the rule for every writer.
* **Is the hash chain secure?** It makes tampering *evident*, not impossible (see below).

## Limitations to state yourself

* **Severity weights are my choice, not validated.** The estimate is rule-based (`SeverityService`), and the three checkboxes are still self-reported; the audited manager override is the safeguard.
* **Banker's pool is shared.** Ordinary requests also draw from the depot, so the safety guarantee is relative to the camps' *declared* claims. If ordinary requests drain a resource below a camp's need, the state shows UNSAFE.
* **Simulator burst model is an assumption.** For live requests, handling time = 3 + quantity ÷ 25 minutes (clamped 2-40). The seeded workloads are synthetic.
* **Audit chain is tamper-evident, not tamper-proof.** Someone with full database write access could recompute every hash. Publishing the head hash somewhere external would close that gap.
* **Login throttle is in memory** (one server instance, reset on restart).
* **The AI model is trained on synthetic data.** Its metrics describe that data, not real-world performance. The scheduler works without it.
* **The Concurrency Lab uses a scratch table**, so it demonstrates the mechanisms but not contention on the real inventory.
* **Evaluation numbers on the Overview come from simulation** (`os_sim/reports`), and the last three policies differ only slightly.
* **Demo passwords and the JWT secret are in plain configuration.** Fine for a local demo; use environment variables and `cras.seed.enabled=false` for anything else.
