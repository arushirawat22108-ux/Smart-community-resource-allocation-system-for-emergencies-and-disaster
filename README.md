# CRAS · Community Resource Allocation System (Phase III)

Spring Boot + MySQL backend, a dependency-free JavaScript frontend, and an optional Python AI service.
Emergency requests (water, food, medical kits, blankets) are prioritised, scheduled and allocated like processes in an
operating system, with database-level safeguards underneath.

## Run it

You need **JDK 17+**, **Maven**, **MySQL 8** (running) and, for the optional AI page, **Python 3.10+**.

| Step | Command | Result |
|---|---|---|
| 1. Backend | `cd backend && mvn spring-boot:run` (or `start_backend.bat`) | API on http://localhost:8080 |
| 2. Frontend | `cd frontend && python -m http.server 5500` (or `start_frontend.bat`) | UI on http://localhost:5500 |
| 3. AI (optional) | `pip install -r requirements.txt` then `python ai/serve.py --port 8001` | Risk Oracle page |

**Set your MySQL password first.** Either edit the default in `backend/src/main/resources/application.properties`
(`spring.datasource.password=${DB_PASSWORD:root}` → replace `root`) or set the `DB_PASSWORD` environment variable.
The database `cras_db` is created automatically.

On a healthy first start the console prints `[dbms] bootstrap finished: N steps, 0 failed` and `[seed] demo data ready`.

### Demo accounts

| Role | E-mail | Password |
|---|---|---|
| Admin | admin@cras.local | `CRAS_Admin#82Xq!` |
| Resource manager | manager@cras.local | `CRAS_Manager#73Lp!` |
| Community user | user@cras.local | `CRAS_User#91Km!` |

These live in **one** place for the backend, `cras.seed.*` in `application.properties`, and are re-applied to the
database on every start. The sign-in page's demo buttons read the same values from `frontend/js/config.js`
(set `DEMO_AUTOFILL: false` there to stop the buttons filling in passwords).


## What is new in this version

**Operating systems**
* **Scheduler simulator** (OS Lab): FCFS, SJF, SRTF, static priority, round robin and CRAS aging on the same workload, with Gantt charts and metrics (average/critical/maximum wait, starvation count, preemptions, Jain fairness). Three workloads: mixed burst, steady urgent stream (starvation test), and your live queue.
* **Banker's algorithm on live data**: relief camps hold and request multiple resources from the real depot stock; unsafe requests are refused with the safe sequence shown.
* **Autopilot**: the scheduler as a background daemon (`@Scheduled`), with optional preemption and a manual "run one tick" button.
* **Process-state view**: request lifecycle mapped to Ready / Running / Terminated / Killed, with live counts.
* Existing: priority with aging, preemption, backfilling, critical reservation.

**Databases**
* **Concurrency Lab**: race condition vs pessimistic locking vs optimistic versioning vs atomic UPDATE; isolation-level anomalies (dirty, non-repeatable, phantom); deadlock vs ordered locking. All on a scratch table.
* **Triggers + stock ledger**: stock can never go negative and every change is recorded, even manual SQL; reconciliation proves `stock = sum(ledger)`.
* **Stored procedure** `sp_allocate`, **views**, a **CHECK constraint**, a composite descending **index** matched to the scheduler query, an **EXPLAIN** viewer.
* **Analytics** with window functions (`RANK`, running `SUM`) and a recursive CTE.
* **Idempotency keys**: double clicks and retries create a request exactly once.
* **Tamper-evident audit log**: SHA-256 hash chain, verifiable from the UI.
* **Server-Sent Events**: live updates, delivered only after the database commit.
* **Automatic severity**: nobody types a 1-10 score. The server estimates it from location risk, stock level, resource type, nearby waiting requests and three yes/no answers, shows the reasons before submitting, and a manager can override it (audited). Weights are a transparent first guess in `SeverityService.compute()`, not validated on real emergencies.
* **Security**: login throttling (5 failures → 60 s lockout), role-based visibility (community users see masked identities and only their own requests).

Everything database-related installs itself on start-up (`DbmsBootstrap.java`); `db/01_dbms_objects_reference.sql`
is a readable copy. If any step fails, the app still starts and **DBMS Lab → Schema objects** shows which step and why.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "Invalid email or password (n attempts left)" | Password differs from `application.properties`. Use the demo button, or copy the password from the table above. |
| "Too many failed sign-in attempts" | Lockout; wait 60 s (`cras.security.lockout-seconds`). |
| "Cannot reach the CRAS server" | Backend not running, or `API_URL` in `frontend/js/config.js` is wrong. |
| `Access denied for user 'root'` at start-up | Set `DB_PASSWORD` or edit `application.properties`. |
| Port 8080 / 5500 already in use | Stop the other process or change the port. |
| Schema objects shows FAILED steps | Read the error column. Usually a MySQL privilege (needs `TRIGGER`, `CREATE ROUTINE`) or an old object with a clashing name. |
| Map shows "unavailable offline" | The map and fonts load from CDNs; everything else works offline. |
| Risk Oracle says AI offline | Start `python ai/serve.py --port 8001`. CRAS falls back to its rule-based formula. |

## Layout

```
backend/    Spring Boot (controller / service / repository / entity, os/ = pure algorithms)
frontend/   index.html, css/styles.css, js/*.js  (no build step)
ai/         shortage model + service (synthetic training data)
os_sim/     offline scheduler comparison and Banker reference (Python)
db/         reference SQL and manual demos
verification/  checks that need no database
docs/VIVA_GUIDE.md   what to demo and what to say
```

## What was verified, and what was not

Verified while building this package:
* the scheduler, Banker's and audit-hash classes compile and pass `verification/run_java_checks.sh` (textbook results, starvation and aging, tamper scenarios);
* the whole backend type-checks against hand-written stubs of the Spring/JPA APIs it uses;
* the frontend ran in a real browser against a mock API: sign-in (including the failure you hit), live events, every page, role restrictions, session restore, no JavaScript errors. The scheduler charts in that test came from the real simulator.

**Not verified here:** the backend has not been started against a real MySQL server or the real Spring/Hibernate jars (neither was available).
Expect to run it first on your machine. The riskiest parts are the MySQL-specific pieces: trigger/procedure/view creation, the CHECK constraint,
the analytics queries and the concurrency lab. They are isolated so a failure is reported in the UI instead of stopping the application.
If something fails, send me the console line or the Schema-objects error text.
