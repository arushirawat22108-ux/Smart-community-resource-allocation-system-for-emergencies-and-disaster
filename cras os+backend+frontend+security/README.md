# CRAS — Complete DBMS + OS + Security + Frontend + 5 Added Features

This is the enhanced non-AI version of the Community Resource Allocation System.

AI/ML prediction and confidence scoring are intentionally excluded.

## Added 5 features

### 1. Resource Manager Dashboard
- Live counts for resources, pending, allocated, preempted and completed requests
- Inventory cards
- Dispatch Queue action

### 2. OS Scheduling Visualization
- Priority queue table
- Resource-specific queues
- Dynamic priority recalculation
- Aging through waiting time
- Scheduler dispatch action
- Preemption simulator

### 3. Allocation + Preemption Simulation
- Transactional allocation
- Inventory reduction
- Lower-priority allocation selection
- Preemption and inventory release
- Immediate retry of critical allocation
- Allocation status/history

### 4. Admin + Audit Panel
- User list
- Allocation history
- Audit log
- Summary counters
- Role-protected admin endpoints

### 5. GIS Map
- Leaflet + OpenStreetMap
- Location markers
- Risk level popup
- Emergency request markers
- Request priority popup

## Existing DBMS features

- MySQL
- JPA relational entities
- Users
- Roles
- Locations
- Resources
- Emergency requests
- Allocations
- Audit logs
- Database indexes
- Transactions
- Pessimistic row locking / SELECT FOR UPDATE

## Existing OS features

- Priority scheduling
- Priority aging
- Resource-specific ready/waiting queues
- Starvation prevention
- Preemption
- Limited-resource handling
- Allocation states

## Existing security

- Spring Security
- JWT authentication
- BCrypt password hashing
- Role-based authorization
- COMMUNITY_USER / RESOURCE_MANAGER / ADMIN
- Public registration can create COMMUNITY_USER only
- Audit logging

## Run

### 1. MySQL

Create the database:

```sql
CREATE DATABASE cras_db;
```

Open:

`backend/src/main/resources/application.properties`

Change:

```properties
spring.datasource.password=YOUR_MYSQL_PASSWORD
```

to your MySQL root password.

### 2. Backend

Requirements:
- Java 17
- Maven

In VS Code terminal:

```bash
cd backend
mvn spring-boot:run
```

Test:

```text
http://localhost:8080/api/health
```

### 3. Frontend

Requirements:
- Node.js
- npm

Open a second terminal:

```bash
cd frontend
npm install
npm run dev
```

Open the Vite URL, normally:

```text
http://localhost:5173
```

## Demo accounts

Community:

```text
user@cras.local
User@123
```

Resource manager:

```text
manager@cras.local
Manager@123
```

Admin:

```text
admin@cras.local
Admin@123
```

## Demonstration order

### Demo 1 — Community request
1. Login as community user.
2. Open Requests.
3. Select Water.
4. Enter quantity/severity/people/scarcity.
5. Submit.
6. Show the request entering the priority queue.

### Demo 2 — OS scheduling
1. Login as manager.
2. Open OS Scheduler.
3. Show requests ordered by priority.
4. Explain that waiting time contributes aging points.
5. Select Water.
6. Click Run Scheduler.
7. Show the highest-priority request being selected.

### Demo 3 — DBMS allocation
1. Dispatch/allocate a request.
2. Explain that allocation is transactional.
3. Explain that the resource row is pessimistically locked.
4. Show inventory decreasing.
5. Show allocation history.

### Demo 4 — Preemption
1. Create an active lower-priority allocation.
2. Create a higher-priority request for the same resource.
3. Open OS Scheduler.
4. Enter the critical request ID in Preemption Simulator.
5. Click Run Preemption.
6. Explain that lower-priority active allocations can be reclaimed.

### Demo 5 — Security/Admin
1. Login as admin.
2. Open Admin & Audit.
3. Show users, allocations and audit events.
4. Explain RBAC and JWT.

### Demo 6 — GIS
1. Open GIS Map.
2. Show registered locations.
3. Show emergency request markers.
4. Click a marker to display request priority.

## Priority formula

The current non-AI formula is:

```text
Severity       35%
Affected       20%
Scarcity       20%
Location Risk  15%
Aging          10%
```

AI prediction/confidence is NOT included.

## Later AI integration

The future Phase-3/AI path can be:

```text
Historical Files
      ↓
Data Preprocessing
      ↓
Prediction Model
      ↓
Prediction + Confidence
      ↓
Priority Calculation
      ↓
OS Scheduler
      ↓
DBMS Allocation
```

The current PriorityService is deliberately isolated so the AI score can be added later without rebuilding the database or scheduler.

## Note

The GIS map uses OpenStreetMap tiles through Leaflet. An internet connection is needed for map tiles during the demo.
